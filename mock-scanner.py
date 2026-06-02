#!/usr/bin/env python3
"""
模拟扫描器，用于测试扫描流水线
subfinder/naabu/httpx 保持模拟输出
nuclei 改为基于真实 HTTP 请求的漏洞探测
"""
import sys
import json
import urllib.request
import urllib.error
import socket

def mock_subfinder():
    domain = None
    for i, arg in enumerate(sys.argv):
        if arg == '-d' and i + 1 < len(sys.argv):
            domain = sys.argv[i + 1]
            break
    if domain and '127.0.0.1' not in domain:
        print(f"www.{domain}")
        print(f"api.{domain}")
        print(f"admin.{domain}")

def mock_naabu():
    for i, arg in enumerate(sys.argv):
        if arg == '-list' and i + 1 < len(sys.argv):
            with open(sys.argv[i + 1]) as f:
                targets = f.read().strip().split('\n')
            for t in targets:
                t = t.strip()
                if not t:
                    continue
                if '127.0.0.1' in t:
                    print(f"127.0.0.1:5000")
                    print(f"127.0.0.1:8080")
                else:
                    print(f"{t}:80")
                    print(f"{t}:443")
            break

def mock_httpx():
    for i, arg in enumerate(sys.argv):
        if arg == '-list' and i + 1 < len(sys.argv):
            with open(sys.argv[i + 1]) as f:
                targets = f.read().strip().split('\n')
            for t in targets:
                t = t.strip()
                if not t:
                    continue
                if '127.0.0.1:5000' in t:
                    print("http://127.0.0.1:5000")
                elif '127.0.0.1:8080' in t:
                    print("http://127.0.0.1:8080")
                elif ':' in t:
                    print(f"http://{t}")
                else:
                    print(f"http://{t}")
                    print(f"https://{t}")
            break

def check_git_config(url):
    """真实探测 Git 配置泄露"""
    try:
        req = urllib.request.Request(f"{url}/.git/config", headers={'User-Agent': 'Mozilla/5.0'}, method='GET')
        with urllib.request.urlopen(req, timeout=3) as resp:
            body = resp.read().decode('utf-8', errors='ignore')
            if resp.status == 200 and '[core]' in body and 'repositoryformatversion' in body:
                return True
    except Exception:
        pass
    return False

def check_env_file(url):
    """真实探测环境变量文件泄露"""
    try:
        req = urllib.request.Request(f"{url}/.env", headers={'User-Agent': 'Mozilla/5.0'}, method='GET')
        with urllib.request.urlopen(req, timeout=3) as resp:
            body = resp.read().decode('utf-8', errors='ignore')
            if resp.status == 200 and ('SECRET' in body or 'DATABASE_URL' in body or 'AWS_' in body):
                return True
    except Exception:
        pass
    return False

def check_admin_panel(url):
    """真实探测未授权管理面板"""
    try:
        req = urllib.request.Request(f"{url}/admin", headers={'User-Agent': 'Mozilla/5.0'}, method='GET')
        with urllib.request.urlopen(req, timeout=3) as resp:
            body = resp.read().decode('utf-8', errors='ignore')
            if resp.status == 200 and ('Admin Panel' in body or 'No authentication' in body):
                return True
    except Exception:
        pass
    return False

def real_nuclei_scan():
    """基于真实 HTTP 请求的漏洞探测"""
    output_file = None
    input_file = None
    for i, arg in enumerate(sys.argv):
        if arg == '-o' and i + 1 < len(sys.argv):
            output_file = sys.argv[i + 1]
        if arg == '-list' and i + 1 < len(sys.argv):
            input_file = sys.argv[i + 1]

    findings = []
    if input_file:
        with open(input_file) as f:
            targets = f.read().strip().split('\n')
        for t in targets:
            t = t.strip()
            if not t:
                continue
            # 只探测真实可达的目标
            try:
                req = urllib.request.Request(t, headers={'User-Agent': 'Mozilla/5.0'}, method='HEAD')
                urllib.request.urlopen(req, timeout=2)
            except Exception:
                continue

            if check_git_config(t):
                findings.append({
                    "template-id": "git-config",
                    "template": "http/exposures/configs/git-config.yaml",
                    "info": {
                        "name": "Git Configuration Exposure",
                        "severity": "high",
                        "description": "Git configuration file was exposed, potentially leaking repository metadata and credentials."
                    },
                    "matched-at": f"{t}/.git/config",
                    "type": "http"
                })

            if check_env_file(t):
                findings.append({
                    "template-id": "env-file",
                    "template": "http/exposures/configs/env-file.yaml",
                    "info": {
                        "name": "Environment Variable File Exposure",
                        "severity": "critical",
                        "description": "Environment file (.env) was exposed, containing sensitive credentials and configuration."
                    },
                    "matched-at": f"{t}/.env",
                    "type": "http"
                })

            if check_admin_panel(t):
                findings.append({
                    "template-id": "unauth-admin-panel",
                    "template": "http/misconfiguration/unauth-admin-panel.yaml",
                    "info": {
                        "name": "Unauthenticated Admin Panel",
                        "severity": "high",
                        "description": "Admin panel is accessible without authentication."
                    },
                    "matched-at": f"{t}/admin",
                    "type": "http"
                })

    if output_file:
        with open(output_file, 'w') as f:
            for finding in findings:
                f.write(json.dumps(finding) + '\n')
    else:
        for finding in findings:
            print(json.dumps(finding))

def show_version():
    print("mock-scanner v2.0.0")

if __name__ == '__main__':
    args = sys.argv
    if '-version' in args or '--version' in args:
        show_version()
        sys.exit(0)

    prog = args[0]
    if 'subfinder' in prog:
        mock_subfinder()
    elif 'naabu' in prog:
        mock_naabu()
    elif 'httpx' in prog:
        mock_httpx()
    elif 'nuclei' in prog:
        real_nuclei_scan()
    else:
        if '-d' in args:
            mock_subfinder()
        elif '-jsonl' in args or '-o' in args:
            real_nuclei_scan()
        elif '-silent' in args:
            mock_httpx()
        else:
            mock_naabu()
