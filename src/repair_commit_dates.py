#!/usr/bin/env python3
r"""Repair explicit Git author/committer dates while preserving commit contents.

Preview:
    python3 src/repair_commit_dates.py /path/to/dates.txt
Apply locally, with a required new backup:
    python3 src/repair_commit_dates.py /path/to/dates.txt --apply \
        --backup /path/outside/repository/original-history.bundle

Map format: one hexadecimal commit ID and ISO-8601 timestamp per line.
Timestamps require seconds and a timezone (Z or +/-HH:MM). Blank lines and
lines starting with # are ignored. Both author and committer dates are set.
Use --repo PATH to select another repository, including a restored backup.

Dry-run is the default. Applying changes local branch and remote-tracking refs;
it never fetches or pushes. Changed ancestors also change descendant IDs, so
invalidated commit signatures are removed. Other signatures remain intact.
Affected tags must be handled separately. Exact historical dates cannot be
recovered from parent order; this tool never invents replacement dates.
"""

import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class RepairError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise RepairError(message)


class Git:
    def __init__(self, repo):
        self.repo = repo
        # Keep credentials/PATH, but ignore inherited repository/index overrides.
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env['GIT_NO_REPLACE_OBJECTS'] = '1'
        self.env['GIT_OPTIONAL_LOCKS'] = '0'

    def __call__(self, *args, data=None):
        result = subprocess.run(
            ['git', '-C', str(self.repo), *args], input=data,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env,
        )
        if result.returncode:
            raise RepairError(result.stderr.decode(errors='replace').strip())
        return result.stdout

    def refs(self):
        output = self('for-each-ref', '--format=%(refname) %(objectname) %(symref)',
                      'refs/heads', 'refs/remotes').decode()
        return {p[0]: p[1] for line in output.splitlines()
                if len(p := line.split()) == 2}  # Symbolic refs follow their target.


def parse_commit(raw):
    header, message = raw.split(b'\n\n', 1)
    fields = []
    for line in header.split(b'\n'):
        if line.startswith(b' '):
            fields[-1] += b'\n' + line
        else:
            fields.append(line)
    return fields, message


def key(field):
    return field.split(b' ', 1)[0]


def values(fields, name):
    return [f.split(b' ', 1)[1] for f in fields if key(f) == name]


def load_dates(git, path, reachable):
    dates = {}
    for number, line in enumerate(path.read_text().splitlines(), 1):
        if not line.strip() or line.lstrip().startswith('#'):
            continue
        parts = line.split()
        require(len(parts) == 2, f'Line {number}: expected COMMIT ISO_TIMESTAMP')
        prefix, iso = parts
        require(re.fullmatch(r'[0-9a-fA-F]{7,64}', prefix),
                f'Line {number}: use a hexadecimal commit ID, not a revision expression')
        oid = git('rev-parse', '--verify', prefix + '^{commit}').decode().strip()
        require(oid in reachable,
                f'Line {number}: {prefix} is not reachable from current branches. '
                'The history may have been rewritten; use the intended repository and map.')
        require(oid not in dates, f'Line {number}: duplicate commit {prefix}')
        require(re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(Z|[+-](?:[01]\d|2[0-3]):[0-5]\d)', iso),
                f'Line {number}: timestamp needs whole seconds and a timezone, e.g. +/-HH:MM')
        try:
            when = datetime.fromisoformat(iso.replace('Z', '+00:00'))
        except ValueError as exc:
            raise RepairError(f'Line {number}: invalid timestamp: {iso}') from exc
        require(when.utcoffset().total_seconds() % 60 == 0,
                f'Line {number}: timezone must use whole minutes')
        require(when.timestamp() >= 0, f'Line {number}: dates before the Unix epoch are unsupported')
        dates[oid] = f'{int(when.timestamp())} {when.strftime("%z")}'.encode()
    require(dates, 'The date map has no entries')
    return dates


def prepare(git, refs, dates_path):
    order = git('rev-list', '--reverse', '--topo-order', '--stdin',
                data=('\n'.join(sorted(set(refs.values()))) + '\n').encode()).decode().splitlines()
    dates = load_dates(git, dates_path, set(order))
    mapping, originals, revised, new_objects, removed_signatures = {}, {}, {}, {}, []
    for old in order:
        raw = git('cat-file', 'commit', old)
        fields, message = parse_commit(raw)
        originals[old] = (fields, message)
        updated = []
        for field in fields:
            name = key(field)
            if name == b'parent':
                field = b'parent ' + mapping[field.split(b' ', 1)[1].decode()].encode()
            elif name in (b'author', b'committer') and old in dates:
                field = field.rsplit(b' ', 2)[0] + b' ' + dates[old]
            updated.append(field)
        if updated != fields:
            require(not values(updated, b'mergetag'),
                    f'{old}: rewriting a commit with an embedded merge tag needs separate review')
            if any(key(f) in (b'gpgsig', b'gpgsig-sha256') for f in updated):
                removed_signatures.append(old)
            updated = [f for f in updated if key(f) not in (b'gpgsig', b'gpgsig-sha256')]
        new_raw = b'\n'.join(updated) + b'\n\n' + message
        new = git('hash-object', '-t', 'commit', '--stdin', data=new_raw).decode().strip()
        mapping[old] = new
        revised[new] = parse_commit(new_raw)
        if new != old:
            new_objects[new] = new_raw

    # Audit every commit's bytes and every parent edge before writing objects.
    require(len(set(mapping.values())) == len(mapping), 'The proposed rewrite collapses distinct commits')
    dates_changed = []
    for old, new in mapping.items():
        before, message = originals[old]
        after, new_message = revised[new]
        require(message == new_message and values(before, b'tree') == values(after, b'tree'),
                f'{old}: content preservation failed')
        require(values(after, b'parent') == [mapping[p.decode()].encode() for p in values(before, b'parent')],
                f'{old}: parent topology changed')
        for who in (b'author', b'committer'):
            old_identity, old_time, old_zone = values(before, who)[0].rsplit(b' ', 2)
            new_identity, new_time, new_zone = values(after, who)[0].rsplit(b' ', 2)
            expected = dates.get(old, old_time + b' ' + old_zone)
            require(old_identity == new_identity and new_time + b' ' + new_zone == expected,
                    f'{old}: unexpected {who.decode()} change')
            for parent in values(after, b'parent'):
                parent_time = values(revised[parent.decode()][0], who)[0].rsplit(b' ', 2)[1]
                require(int(new_time) >= int(parent_time),
                        f'{old}: proposed {who.decode()} date precedes parent {parent.decode()}')
        ignored = {b'parent', b'author', b'committer'}
        if old in removed_signatures:
            ignored |= {b'gpgsig', b'gpgsig-sha256'}
        require([f for f in before if key(f) not in ignored] == [f for f in after if key(f) not in ignored],
                f'{old}: unexpected header change')
        if old in dates and any(values(before, who) != values(after, who) for who in (b'author', b'committer')):
            dates_changed.append(old)

    for tag in git('for-each-ref', '--format=%(refname)', 'refs/tags').decode().splitlines():
        target = git('rev-parse', tag + '^{}').decode().strip()
        require(mapping.get(target, target) == target,
                f'{tag}: tag points into rewritten history; handle this tag explicitly first')
    updates = {ref: {'old': old, 'new': mapping[old]}
               for ref, old in refs.items() if mapping[old] != old}
    report = {
        'mode': 'dry-run', 'repository': str(git.repo),
        'commits_checked': len(mapping), 'dates_requested': len(dates),
        'dates_changed': dates_changed, 'commit_ids_changed': len(new_objects),
        'signatures_removed': removed_signatures,
        'trees_messages_identities_and_topology_preserved': True,
        'unselected_dates_preserved': True, 'parent_date_violations': 0,
        'ref_updates': updates, 'commit_map': mapping,
    }
    return report, new_objects


def apply(git, refs, report, objects, backup):
    bare = git('rev-parse', '--is-bare-repository').strip() == b'true'
    if not bare:
        require(not git('status', '--porcelain', '--untracked-files=all'),
                'Apply requires a clean working tree, including untracked files')
    head = git('symbolic-ref', '-q', 'HEAD').decode().strip()
    require(head in refs, 'Apply requires HEAD to point to a selected branch')
    backup = backup.resolve()
    audit_path = backup.with_name(backup.name + '.audit.json')
    repo_root = (Path(git('rev-parse', '--show-toplevel').decode().strip())
                 if not bare else git.repo)
    require(backup != repo_root and repo_root not in backup.parents,
            'Place the backup outside the repository')
    require(not backup.exists() and not audit_path.exists(), 'Backup and audit paths must be new')
    require(git.refs() == refs, 'Repository refs changed during planning; rerun the preview')
    backup.parent.mkdir(parents=True, exist_ok=True)
    git('bundle', 'create', str(backup), '--all')
    git('bundle', 'verify', str(backup))
    for oid, raw in objects.items():
        written = git('hash-object', '-t', 'commit', '-w', '--stdin', data=raw).decode().strip()
        require(written == oid and git('cat-file', 'commit', oid) == raw,
                f'{oid}: written object verification failed')
    git('fsck', '--full', '--no-dangling')
    require(git.refs() == refs, 'Repository refs changed during preparation; backup retained, refs not updated')
    if not bare:
        require(not git('status', '--porcelain', '--untracked-files=all'),
                'Working tree changed during preparation; refs not updated')
    report['mode'] = 'prepared'
    report['backup'] = str(backup)
    audit_path.write_text(json.dumps(report, indent=2) + '\n')
    transaction = ['start']
    for ref, old in refs.items():
        if ref in report['ref_updates']:
            transaction.append(f'update {ref} {report["ref_updates"][ref]["new"]} {old}')
        else:
            transaction.append(f'verify {ref} {old}')
    transaction += ['prepare', 'commit', '']
    git('update-ref', '-m', 'Repair explicit commit dates (backup recorded)', '--stdin',
        data='\n'.join(transaction).encode())
    report['mode'] = 'applied locally; no remote push performed'
    audit_path.write_text(json.dumps(report, indent=2) + '\n')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('dates', type=Path, help='Explicit commit/date map')
    parser.add_argument('--repo', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--apply', action='store_true', help='Apply locally after backup and verification')
    parser.add_argument('--backup', type=Path, help='New bundle path outside repository; required for --apply')
    args = parser.parse_args(argv)
    try:
        require(not args.apply or args.backup, '--apply requires --backup PATH')
        git = Git(args.repo.resolve())
        require(git('rev-parse', '--is-shallow-repository').strip() == b'false', 'A complete repository is required')
        require(not git('for-each-ref', '--format=%(refname)', 'refs/replace'), 'Remove or separately review replace refs first')
        graft = Path(git('rev-parse', '--git-path', 'info/grafts').decode().strip())
        if not graft.is_absolute():
            graft = git.repo / graft
        require(not graft.exists(), 'Legacy grafts require separate review')
        refs = git.refs()
        require(refs, 'No local or remote-tracking branches found')
        report, objects = prepare(git, refs, args.dates)
        if args.apply and objects:
            apply(git, refs, report, objects, args.backup)
        elif args.apply:
            report['mode'] = 'no changes needed'
        print(json.dumps(report, indent=2))
        return 0
    except (RepairError, OSError, ValueError) as exc:
        print(f'error: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
