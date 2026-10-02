#!/usr/bin/env python3
"""Read-only preflight for a real LiuChat proxy/two-backend acceptance network."""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def server_id(path):
    text = path.read_text(encoding='utf-8')
    match = re.search(r'^server:\s*(.*?)\s*$', text, re.MULTILINE)
    if not match:
        raise ValueError('missing top-level server identifier')
    value = match.group(1)
    quoted = re.match(r'''^(['"])(.*?)\1\s*(?:#.*)?$''', value)
    if quoted:
        value = quoted.group(2)
    else:
        value = value.split('#', 1)[0].strip()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', value):
        raise ValueError('preflight requires a simple nonempty server identifier')
    return value


def proxy_servers(text, proxy):
    if proxy == 'velocity':
        section = re.search(r'^\[servers\]\s*\n(.*?)(?=^\[|\Z)', text, re.MULTILINE | re.DOTALL)
        if not section:
            return set()
        return set(re.findall(r'^\s*([A-Za-z0-9_-]+)\s*=\s*["\']', section.group(1), re.MULTILINE))
    section = re.search(r'^servers:\s*\n(.*?)(?=^\S|\Z)', text, re.MULTILINE | re.DOTALL)
    if not section:
        return set()
    return set(re.findall(r'^  ([A-Za-z0-9_-]+):\s*$', section.group(1), re.MULTILINE))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--proxy', required=True, choices=['velocity', 'bungee'])
    parser.add_argument('--proxy-config', required=True, type=Path)
    parser.add_argument('--backend-a', required=True, type=Path)
    parser.add_argument('--backend-b', required=True, type=Path)
    parser.add_argument('--shared', required=True, type=Path)
    args = parser.parse_args()
    failures = []

    def check(label, operation):
        try:
            detail = operation()
            print(f'PASS {label}' + (f': {detail}' if detail else ''))
        except (OSError, ValueError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
            failures.append(label)
            # Config contents, secrets and command output are intentionally never printed.
            print(f'FAIL {label}: {type(error).__name__}')

    def java():
        executable = shutil.which('java')
        if not executable:
            raise ValueError('java not found')
        result = subprocess.run([executable, '-version'], capture_output=True, text=True, timeout=10, check=True)
        match = re.search(r'version "(\d+)', result.stdout + result.stderr)
        if not match or int(match.group(1)) < 21:
            raise ValueError('Java 21 or newer required')
        return f'Java {match.group(1)} (server JVMs must be checked separately)'

    check('Java', java)
    digest = None

    def artifact():
        nonlocal digest
        with zipfile.ZipFile(args.jar) as archive:
            metadata = archive.read('plugin.yml').decode('utf-8')
            if not re.search(r'^name:\s*LiuChat\s*$', metadata, re.MULTILINE):
                raise ValueError('not a LiuChat main jar')
            if 'com/liu/liuchat/LiuChat.class' not in archive.namelist():
                raise ValueError('main class missing')
        digest = sha256(args.jar)
        return f'{args.jar.name}, SHA-256 {digest}'

    check('Candidate artifact', artifact)
    ids = []
    for label, backend in [('backend-a', args.backend_a), ('backend-b', args.backend_b)]:
        def backend_check(backend=backend):
            identity = server_id(backend / 'plugins/LiuChat/config.yml')
            ids.append(identity)
            jars = []
            for jar in (backend / 'plugins').glob('*.jar'):
                with zipfile.ZipFile(jar) as archive:
                    if 'plugin.yml' not in archive.namelist():
                        continue
                    metadata = archive.read('plugin.yml').decode('utf-8')
                    if re.search(r'^name:\s*LiuChat\s*$', metadata, re.MULTILINE):
                        jars.append(jar)
            if len(jars) != 1 or digest is None or sha256(jars[0]) != digest:
                raise ValueError('exactly one matching LiuChat main jar required')
            return identity
        check(label, backend_check)

    def uniqueness():
        if args.backend_a.resolve() == args.backend_b.resolve() or len(ids) != 2 or ids[0] == ids[1]:
            raise ValueError('two distinct backends and identifiers required')
    check('Unique backends and server identifiers', uniqueness)

    def proxy_check():
        text = args.proxy_config.read_text(encoding='utf-8')
        names = proxy_servers(text, args.proxy)
        if len(ids) != 2 or not set(ids).issubset(names):
            raise ValueError('proxy server names must match local identifiers')
        if args.proxy == 'velocity' and not re.search(
                r'^\s*bungee-plugin-message-channel\s*=\s*true\s*(?:#.*)?$', text, re.MULTILINE):
            raise ValueError('explicitly enable bungee-plugin-message-channel')
        return args.proxy
    check('Proxy routing configuration', proxy_check)

    def shared_check():
        if not args.shared.is_dir() or not os.access(args.shared, os.R_OK | os.W_OK | os.X_OK):
            raise ValueError('shared directory not accessible to current user')
        return 'accessible to current user; verify backend service users and mount paths manually'
    check('Shared directory', shared_check)
    print('Preflight only: client login, forwarding, rendering, YAML semantics and runtime behavior remain unverified.')
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
