#!/usr/bin/env python3
"""Official gh device login bridge. Emits only a fixed status schema, never raw CLI logs/tokens.

Protocol checked against cli/cli v2.45.0 (Ubuntu 24.04): auth/login/login.go,
internal/authflow/flow.go, auth/logout/logout.go and cli.github.com/manual.
"""
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time

GH = '/usr/bin/gh'
DEVICE_URL = 'https://github.com/login/device'

def emit(kind, **fields):
    print(json.dumps(dict(kind=kind, **fields)), flush=True)

def environment():
    # The official agent owns credentials in its ordinary private config directory.
    # Never inherit a developer's token, debug transport logging or browser command.
    env = {key: value for key, value in os.environ.items()
           if key not in {'GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN',
                          'GH_DEBUG', 'DEBUG', 'GH_BROWSER', 'BROWSER', 'GH_CONFIG_DIR', 'XDG_CONFIG_HOME'}}
    env.update({'HOME': '/root', 'LANG': 'C.UTF-8', 'NO_COLOR': '1', 'GH_PROMPT_DISABLED': '1',
                'GH_NO_UPDATE_NOTIFIER': '1', 'GH_HOST': 'github.com', 'GIT_TERMINAL_PROMPT': '0',
                'GH_CONFIG_DIR': '/root/.config/gh'})
    return env

def run(arguments, timeout=90, capture=False):
    return subprocess.run(arguments, stdin=subprocess.DEVNULL,
                          stdout=subprocess.PIPE if capture else subprocess.DEVNULL,
                          stderr=subprocess.DEVNULL, env=environment(), timeout=timeout, text=True)

def identity():
    valid = run([GH, 'auth', 'status', '--hostname', 'github.com']).returncode == 0
    if not valid:
        return ''
    account = run([GH, 'api', '--hostname', 'github.com', 'user', '--jq', '.login'], capture=True)
    login = account.stdout.strip()
    return login if account.returncode == 0 and re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})', login) else ''

def status():
    if not Path(GH).is_file():
        emit('state', installed=False, connected=False, account='', version='', status='Install GitHub CLI to connect your GitHub account.')
        return
    version = run([GH, '--version'], capture=True).stdout.splitlines()
    value = version[0] if version and re.fullmatch(r'gh version [0-9A-Za-z.()+_ -]{1,120}', version[0]) else 'GitHub CLI'
    login = identity()
    emit('state', installed=True, connected=bool(login), gitReady=bool(login), account=login, version=value,
         status=('GitHub account verified: ' + login) if login else 'GitHub sign-in is absent or could not be verified. Connect or retry on a working network.')

def install():
    emit('progress', status="Installing GitHub CLI from Ubuntu's signed package repository…")
    options = ['-o', 'APT::Get::AllowUnauthenticated=false', '-o', 'Acquire::AllowInsecureRepositories=false',
               '-o', 'Acquire::AllowDowngradeToInsecureRepositories=false', '-o', 'Dpkg::Lock::Timeout=30']
    env = environment(); env['DEBIAN_FRONTEND'] = 'noninteractive'
    for args in [['apt-get'] + options + ['update'], ['apt-get'] + options + ['install', '-y', '--no-install-recommends', 'gh']]:
        result = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, timeout=900)
        if result.returncode:
            raise RuntimeError('GitHub CLI installation did not finish. Check your network/storage and retry; existing projects and sign-ins were kept.')
    if not Path(GH).is_file() or run([GH, '--version']).returncode:
        raise RuntimeError('Ubuntu did not provide a working GitHub CLI on this phone.')
    emit('progress', status='GitHub CLI installed. Connect your account next.')
    status()

def login_event(line):
    """The only permitted non-status data are the engine's one-time code and fixed device URL."""
    line = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]', '', line)
    match = re.search(r'one-time code:\s*([A-Z0-9]{4}-[A-Z0-9]{4})(?:\s|$)', line)
    if match:
        return {'kind': 'device', 'code': match.group(1), 'url': DEVICE_URL,
                'status': 'Open github.com and enter this one-time code, then return to PocketAgent.'}
    return None

def login():
    if not Path(GH).is_file():
        raise RuntimeError('Install GitHub CLI first.')
    emit('progress', status="Requesting GitHub's official device sign-in…")
    process = subprocess.Popen([GH, 'auth', 'login', '--web', '--hostname', 'github.com', '--git-protocol', 'https'],
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               env=environment())
    selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
    pending = b''; deadline = time.monotonic() + 900
    try:
        while time.monotonic() < deadline:
            events = selector.select(timeout=0.5)
            for key, _ in events:
                data = os.read(key.fileobj.fileno(), 4096)
                if not data:
                    selector.unregister(key.fileobj); continue
                pending += data
                while b'\n' in pending:
                    line, pending = pending.split(b'\n', 1)
                    event = login_event(line.decode('utf-8', errors='replace'))
                    if event:
                        emit(event.pop('kind'), **event)
                # Drop unknown long output entirely; never emit diagnostic/token content.
                if len(pending) > 16384:
                    pending = b''
            if process.poll() is not None and not selector.get_map():
                break
        else:
            raise RuntimeError('GitHub sign-in expired. Tap Connect to request a new code.')
        if process.wait(timeout=10):
            raise RuntimeError('GitHub sign-in did not finish. Retry the official browser flow; no token was copied into PocketAgent.')
    finally:
        selector.close()
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        process.stdout.close()
    account = identity()
    if not account:
        raise RuntimeError('Sign-in returned, but GitHub could not verify the account. Refresh status before using private repositories.')
    emit('progress', status='GitHub account verified. Connecting Git HTTPS credentials…')
    if run([GH, 'auth', 'setup-git', '--hostname', 'github.com']).returncode:
        raise RuntimeError('GitHub signed in, but Git credential setup did not finish. Reconnect to retry; no project was changed.')
    emit('state', installed=True, connected=True, gitReady=True, account=account,
         status='GitHub connected for HTTPS clone, pull and push: ' + account)

def logout(user):
    if not Path(GH).is_file():
        status(); return
    if user and not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})', user):
        raise RuntimeError('Choose a valid GitHub username for local sign-out.')
    args = [GH, 'auth', 'logout', '--hostname', 'github.com']
    if user:
        args += ['--user', user]
    if run(args).returncode:
        raise RuntimeError('GitHub CLI could not remove that saved account. If more than one account exists, enter its GitHub username and retry.')
    remaining = identity()
    emit('state', installed=True, connected=bool(remaining), gitReady=bool(remaining), account=remaining,
         status=('Selected sign-in removed; another GitHub account is active: ' + remaining)
         if remaining else 'This GitHub sign-in was removed locally. Project files are unchanged.')

if __name__ == '__main__':
    try:
        action = sys.argv[1] if len(sys.argv) > 1 else 'status'
        if action == 'status': status()
        elif action == 'install': install()
        elif action == 'login': login()
        elif action == 'logout': logout(sys.argv[2] if len(sys.argv) > 2 else '')
        else: raise RuntimeError('Unknown GitHub action.')
    except subprocess.TimeoutExpired:
        emit('error', status='GitHub did not answer in time. Check the network and retry.')
        sys.exit(1)
    except Exception as failure:
        # Only our own fixed messages are exposed. Raw subprocess failures are not forwarded.
        emit('error', status=str(failure) if type(failure) is RuntimeError else 'The GitHub operation could not complete. Retry after checking workspace and network.')
        sys.exit(1)
