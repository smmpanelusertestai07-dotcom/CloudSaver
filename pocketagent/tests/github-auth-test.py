#!/usr/bin/env python3
"""Pure fake-process tests: no browser, OAuth request, network, token or credential file."""
import contextlib
import importlib.util
import io
import json
import os
import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

source = Path(__file__).resolve().parents[1] / 'app/assets/pocketagent-github-auth.py'
spec = importlib.util.spec_from_file_location('github_bridge', source)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class GitHubAuthTest(unittest.TestCase):
    def output(self, function, *args):
        result = io.StringIO()
        with contextlib.redirect_stdout(result):
            function(*args)
        return [json.loads(line) for line in result.getvalue().splitlines()]

    def test_only_official_device_code_leaves_cli_parser(self):
        event = bridge.login_event('\x1b[33m! First copy your one-time code: ABCD-1234\x1b[0m')
        self.assertEqual(event['code'], 'ABCD-1234')
        self.assertEqual(event['url'], 'https://github.com/login/device')
        for raw in ['oauth_token: ghp_TEST_SECRET', 'token=gho_TEST_SECRET',
                    'Open this URL: https://evil.example/token', 'user: oauth_secret',
                    'one-time code: ABCD-12345', 'one-time code: invalid']:
            self.assertIsNone(bridge.login_event(raw))
        event = bridge.login_event('one-time code: ABCD-1234 https://evil.example/token')
        self.assertNotIn('evil.example', json.dumps(event))

    def test_private_config_and_no_inherited_credentials_or_browser(self):
        keys = ['GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN',
                'GH_DEBUG', 'DEBUG', 'GH_BROWSER', 'BROWSER', 'XDG_CONFIG_HOME']
        with patch.dict(os.environ, {key: 'TEST_SECRET' for key in keys}, clear=True):
            env = bridge.environment()
        for key in keys:
            self.assertNotIn(key, env)
        self.assertEqual(env['GH_CONFIG_DIR'], '/root/.config/gh')
        self.assertEqual(env['HOME'], '/root')
        self.assertEqual(env['GH_HOST'], 'github.com')

    def test_noninteractive_login_bridge_forwards_code_and_verifies_completion(self):
        actual_popen = bridge.subprocess.Popen
        calls = []
        def fake_popen(command, **kwargs):
            calls.append((command, kwargs))
            # An ordinary local child exercises pipe/select/EOF handling, never gh or OAuth.
            return actual_popen([sys.executable, '-c', "print('token=TEST_SECRET'); print('! First copy your one-time code: ABCD-1234')"], **kwargs)
        with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge.subprocess, 'Popen', side_effect=fake_popen), patch.object(bridge, 'identity', return_value='octocat') as identity, patch.object(bridge, 'run', return_value=SimpleNamespace(returncode=0)) as run:
            events = self.output(bridge.login)
        self.assertEqual(calls[0][0], ['/usr/bin/gh', 'auth', 'login', '--web', '--hostname', 'github.com', '--git-protocol', 'https'])
        self.assertEqual(calls[0][1]['stdin'], bridge.subprocess.DEVNULL)
        self.assertEqual([event['code'] for event in events if event['kind'] == 'device'], ['ABCD-1234'])
        self.assertNotIn('TEST_SECRET', json.dumps(events))
        identity.assert_called_once()
        self.assertEqual(run.call_args.args[0], ['/usr/bin/gh', 'auth', 'setup-git', '--hostname', 'github.com'])
        self.assertTrue(events[-1]['connected'])

    def test_successful_cli_exit_without_verified_identity_is_not_connected(self):
        actual_popen = bridge.subprocess.Popen
        def fake_popen(command, **kwargs):
            return actual_popen([sys.executable, '-c', 'pass'], **kwargs)
        with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge.subprocess, 'Popen', side_effect=fake_popen), patch.object(bridge, 'identity', return_value=''), patch.object(bridge, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'could not verify'):
                self.output(bridge.login)
        run.assert_not_called()

    def test_status_failure_cannot_claim_verified_account(self):
        fake = [SimpleNamespace(returncode=0, stdout='gh version 2.45.0 (Ubuntu)\n'),
                SimpleNamespace(returncode=1, stdout='')]
        with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge, 'run', side_effect=fake) as run:
            state = self.output(bridge.status)[0]
        self.assertFalse(state['connected'])
        self.assertFalse(state['gitReady'])
        self.assertEqual(state['account'], '')
        self.assertEqual(run.call_count, 2)

    def test_status_requires_real_identity_and_sanitizes_identity(self):
        for login, expected in [('octocat', True), ('token=TEST_SECRET', False)]:
            fake = [SimpleNamespace(returncode=0, stdout='gh version 2.45.0 (Ubuntu)\n'),
                    SimpleNamespace(returncode=0, stdout=''), SimpleNamespace(returncode=0, stdout=login)]
            with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge, 'run', side_effect=fake) as run:
                state = self.output(bridge.status)[0]
            self.assertEqual(state['connected'], expected)
            self.assertNotIn('TEST_SECRET', json.dumps(state))
            self.assertEqual(run.call_args_list[-1].args[0], ['/usr/bin/gh', 'api', '--hostname', 'github.com', 'user', '--jq', '.login'])

    def test_logout_explicit_account_and_remaining_account_checked(self):
        for remaining in ['', 'second-account']:
            with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge, 'run', return_value=SimpleNamespace(returncode=0)) as run, patch.object(bridge, 'identity', return_value=remaining):
                state = self.output(bridge.logout, 'octocat')[0]
            self.assertEqual(run.call_args.args[0], ['/usr/bin/gh', 'auth', 'logout', '--hostname', 'github.com', '--user', 'octocat'])
            self.assertEqual(state['connected'], bool(remaining))
            self.assertEqual(state['account'], remaining)
        with patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge, 'run') as run:
            with self.assertRaises(RuntimeError):
                bridge.logout('--hostname evil.example')
            run.assert_not_called()

    def test_install_requires_signed_channel_and_exact_package(self):
        with patch.object(bridge.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as run, patch.object(bridge.Path, 'is_file', return_value=True), patch.object(bridge, 'status'):
            self.output(bridge.install)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands[0][0], 'apt-get')
        self.assertEqual(commands[1][-4:], ['install', '-y', '--no-install-recommends', 'gh'])
        for command in commands[:2]:
            self.assertIn('APT::Get::AllowUnauthenticated=false', command)
            self.assertIn('Acquire::AllowInsecureRepositories=false', command)
        for call in run.call_args_list:
            self.assertEqual(call.kwargs['stderr'], bridge.subprocess.DEVNULL)
            self.assertEqual(call.kwargs['stdin'], bridge.subprocess.DEVNULL)


if __name__ == '__main__':
    unittest.main(verbosity=2)
