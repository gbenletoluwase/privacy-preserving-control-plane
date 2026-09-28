"""Integration coverage for date repair; all Git mutations use temporary repos.

Run with: python3 -m unittest discover -s src/test/python -v
"""

from datetime import datetime, timezone, timedelta
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[2] / 'repair_commit_dates.py'
SIGNATURE = b'gpgsig -----BEGIN PGP SIGNATURE-----\n fixture-only\n -----END PGP SIGNATURE-----'


class RepairDatesIntegrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='commit-date-repair-test-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'repo'
        self.repo.mkdir()
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
        self.git('init', '-b', 'main')
        self.git('config', 'user.name', 'Fixture User')
        self.git('config', 'user.email', 'fixture@example.invalid')
        self.root = self.commit('root', [], 1000000000, signature=True)
        self.target = self.commit('target', [self.root], 1000000100)
        self.side = self.commit('side', [self.root], 1000000150)
        self.signed = self.commit('signed descendant', [self.target], 1000000200, signature=True)
        self.merge = self.commit('merge', [self.signed, self.side], 1000000300)
        self.git('update-ref', 'refs/heads/main', self.merge)
        self.git('update-ref', 'refs/heads/side', self.side)
        self.git('update-ref', 'refs/remotes/origin/main', self.merge)
        self.git('symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/main')
        self.git('reset', '--hard', 'main')
        self.map_path = self.base / 'dates.txt'
        self.backup = self.base / 'original.bundle'
        self.write_map([(self.target, 1000000120), (self.side, 1000000170)])

    def git(self, *args, data=None):
        result = subprocess.run(['git', '-C', str(self.repo), *args], input=data,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env)
        if result.returncode:
            raise AssertionError(result.stderr.decode(errors='replace'))
        return result.stdout

    def commit(self, label, parents, seconds, signature=False, extra=None):
        blob = self.git('hash-object', '-w', '--stdin', data=(label + '\n').encode()).strip()
        tree = self.git('mktree', data=b'100644 blob ' + blob + b'\tproof.txt\n').strip()
        fields = [b'tree ' + tree]
        fields += [b'parent ' + p.encode() for p in parents]
        fields += [f'author Original Author <author@example.invalid> {seconds} -0430'.encode(),
                   f'committer Original Committer <committer@example.invalid> {seconds + 10} +0800'.encode(),
                   b'encoding ISO-8859-1', b'x-fixture unchanged header\n continuation']
        if signature:
            fields.append(SIGNATURE)
        if extra:
            fields.append(extra)
        raw = b'\n'.join(fields) + b'\n\n' + label.encode() + b'\n\nMessage bytes: \xff\n'
        return self.git('hash-object', '-t', 'commit', '-w', '--stdin', data=raw).decode().strip()

    def write_map(self, pairs):
        zone = timezone(timedelta(hours=5, minutes=30))
        self.map_path.write_text('# Explicit fixture dates\n\n' + ''.join(
            f'{oid} {datetime.fromtimestamp(seconds, zone).isoformat()}\n'
            for oid, seconds in pairs))

    def run_script(self, *args):
        return subprocess.run([sys.executable, str(SCRIPT), str(self.map_path),
                               '--repo', str(self.repo), *args],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env)

    def snapshot(self):
        git_dir = self.repo / '.git'
        return {str(path.relative_to(git_dir)): path.read_bytes()
                for path in git_dir.rglob('*') if path.is_file()}

    def assert_blocked(self, *args, contains=None):
        before = self.snapshot()
        result = self.run_script(*args)
        self.assertNotEqual(result.returncode, 0, result.stdout.decode())
        if contains:
            self.assertIn(contains, result.stderr.decode())
        self.assertEqual(before, self.snapshot(), 'Rejected repair mutated the repository')
        return result

    def test_dry_run_has_no_repository_writes(self):
        before = self.snapshot()
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        report = json.loads(result.stdout)
        self.assertEqual(report['mode'], 'dry-run')
        self.assertEqual(report['commits_checked'], 5)
        self.assertEqual(report['commit_ids_changed'], 4)
        self.assertEqual(set(report['dates_changed']), {self.target, self.side})
        self.assertEqual(report['signatures_removed'], [self.signed])
        self.assertEqual(before, self.snapshot())

    def test_apply_preserves_bytes_topology_and_untargeted_dates_with_verified_backup(self):
        originals = {oid: self.git('cat-file', 'commit', oid)
                     for oid in (self.root, self.target, self.side, self.signed, self.merge)}
        old_refs = self.git('show-ref')
        old_worktree = (self.repo / 'proof.txt').read_bytes()
        result = self.run_script('--apply', '--backup', str(self.backup))
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        report = json.loads(result.stdout)
        mapping = report['commit_map']
        self.assertEqual(mapping[self.root], self.root)
        self.assertEqual(report['signatures_removed'], [self.signed])
        for old, original in originals.items():
            expected = original
            # Parent replacement cannot affect message text or other headers in this fixture.
            for old_parent, new_parent in mapping.items():
                expected = expected.replace(b'parent ' + old_parent.encode() + b'\n',
                                            b'parent ' + new_parent.encode() + b'\n')
            if old in (self.target, self.side):
                seconds = 1000000120 if old == self.target else 1000000170
                header, message = expected.split(b'\n\n', 1)
                lines = header.split(b'\n')
                lines = [line.rsplit(b' ', 2)[0] + f' {seconds} +0530'.encode()
                         if line.startswith((b'author ', b'committer ')) else line for line in lines]
                expected = b'\n'.join(lines) + b'\n\n' + message
            if old == self.signed:
                expected = expected.replace(SIGNATURE + b'\n', b'')
            self.assertEqual(self.git('cat-file', 'commit', mapping[old]), expected)
        self.assertEqual(self.git('rev-parse', 'main').decode().strip(), mapping[self.merge])
        self.assertEqual(self.git('rev-parse', 'side').decode().strip(), mapping[self.side])
        self.assertEqual(self.git('rev-parse', 'origin/HEAD').decode().strip(), mapping[self.merge])
        self.assertEqual(self.git('symbolic-ref', 'refs/remotes/origin/HEAD').strip(),
                         b'refs/remotes/origin/main')
        self.assertEqual(old_worktree, (self.repo / 'proof.txt').read_bytes())
        self.assertEqual(self.git('status', '--porcelain'), b'')
        self.git('bundle', 'verify', str(self.backup))
        self.assertEqual(self.git('bundle', 'list-heads', str(self.backup)).splitlines()[:-1],
                         old_refs.splitlines())
        audit = json.loads(self.backup.with_name(self.backup.name + '.audit.json').read_text())
        self.assertEqual(audit, report)

    def test_duplicate_map_rejected_without_writes(self):
        self.write_map([(self.target, 1000000120), (self.target, 1000000130)])
        self.assert_blocked(contains='duplicate commit')

    def test_unreachable_map_rejected_without_writes(self):
        unreachable = self.commit('unreachable', [self.root], 1000000050)
        self.write_map([(unreachable, 1000000060)])
        self.assert_blocked(contains='not reachable')

    def test_malformed_timestamps_rejected_without_writes(self):
        for date in ('2001-09-09T01:48:40', '2001-09-09T01:48:40+00:60',
                     '2001-09-09T01:48:40+00:00:30', '2001-09-09T01:48:40.5Z'):
            with self.subTest(date=date):
                self.map_path.write_text(f'{self.target} {date}\n')
                self.assert_blocked(contains='timestamp')

    def test_revision_expression_rejected_without_writes(self):
        self.map_path.write_text('HEAD~1 2001-09-09T01:48:40Z\n')
        self.assert_blocked(contains='hexadecimal')

    def test_parent_and_descendant_chronology_violations_rejected(self):
        for seconds in (999999999, 1000000999):
            with self.subTest(seconds=seconds):
                self.write_map([(self.target, seconds)])
                self.assert_blocked(contains='date precedes parent')

    def test_affected_lightweight_tag_rejected(self):
        self.git('tag', 'release', self.merge)
        self.assert_blocked(contains='tag points into rewritten history')

    def test_affected_annotated_tag_rejected(self):
        self.git('tag', '-a', 'release', '-m', 'Existing release', self.merge)
        self.assert_blocked(contains='tag points into rewritten history')

    def test_unaffected_tag_is_preserved(self):
        self.git('tag', '-a', 'original', '-m', 'Unchanged root', self.root)
        original = self.git('rev-parse', 'refs/tags/original')
        result = self.run_script('--apply', '--backup', str(self.backup))
        self.assertEqual(result.returncode, 0, result.stderr.decode())
        self.assertEqual(self.git('rev-parse', 'refs/tags/original'), original)

    def test_rewritten_mergetag_rejected(self):
        tag = (b'mergetag object ' + self.side.encode() + b'\n type commit\n tag original\n'
               b' tagger Fixture User <fixture@example.invalid> 1000000250 +0000\n \n tag message')
        tip = self.commit('merge with tag', [self.merge, self.side], 1000000400, extra=tag)
        self.git('update-ref', 'refs/heads/main', tip)
        self.git('reset', '--hard', 'main')
        self.assert_blocked(contains='embedded merge tag')

    def test_dirty_tree_blocks_apply(self):
        (self.repo / 'proof.txt').write_text('unsaved user content\n')
        self.assert_blocked('--apply', '--backup', str(self.backup), contains='clean working tree')
        self.assertFalse(self.backup.exists())

    def test_untracked_file_blocks_apply(self):
        (self.repo / 'untracked.txt').write_text('user content\n')
        self.assert_blocked('--apply', '--backup', str(self.backup), contains='clean working tree')

    def test_preexisting_backup_blocks_apply_and_preserves_file(self):
        self.backup.write_bytes(b'existing backup data')
        self.assert_blocked('--apply', '--backup', str(self.backup), contains='must be new')
        self.assertEqual(self.backup.read_bytes(), b'existing backup data')

    def test_backup_inside_repo_blocks_apply(self):
        self.assert_blocked('--apply', '--backup', str(self.repo / 'history.bundle'),
                            contains='outside the repository')


if __name__ == '__main__':
    unittest.main()
