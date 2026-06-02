#!/usr/bin/env python3
"""
模拟漏洞服务，用于测试扫描引擎
"""
from flask import Flask, Response

app = Flask(__name__)

@app.route('/')
def index():
    return '<h1>Welcome to Test App</h1>'

@app.route('/login')
def login():
    return '''
    <html>
    <head><title>Login</title></head>
    <body>
    <h1>Admin Login</h1>
    <form method="post">
        <input type="text" name="username" placeholder="admin">
        <input type="password" name="password" placeholder="password">
        <button>Login</button>
    </form>
    </body>
    </html>
    '''

@app.route('/.git/config')
def git_config():
    # 漏洞1: Git 配置泄露
    config = '''[core]
    repositoryformatversion = 0
    filemode = true
    bare = false
    logallrefupdates = true
[remote "origin"]
    url = https://github.com/example/vulnerable-app.git
    fetch = +refs/heads/*:refs/remotes/origin/*
'''
    return Response(config, mimetype='text/plain')

@app.route('/.env')
def env_file():
    # 漏洞2: 环境变量文件泄露
    env = '''DATABASE_URL=postgresql://admin:secret123@localhost:5432/prod
JWT_SECRET=my-super-secret-jwt-key-do-not-share
AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
'''
    return Response(env, mimetype='text/plain')

@app.route('/api/users')
def api_users():
    # 模拟 API 返回敏感信息
    return Response('''{"users":[{"id":1,"username":"admin","password_hash":"$2b$12$..."},{"id":2,"username":"user","email":"user@test.com"}]}''',
                    mimetype='application/json')

@app.route('/robots.txt')
def robots():
    return Response('User-agent: *\nDisallow: /admin\nDisallow: /api/internal\n',
                    mimetype='text/plain')

@app.route('/admin')
def admin():
    return '<h1>Admin Panel</h1><p>No authentication required!</p>'

@app.after_request
def after_request(response):
    # 故意移除安全响应头，制造漏洞
    response.headers.pop('X-Frame-Options', None)
    response.headers.pop('X-Content-Type-Options', None)
    response.headers.pop('Content-Security-Policy', None)
    response.headers.pop('Strict-Transport-Security', None)
    response.headers['Server'] = 'Apache/2.4.41 (Ubuntu)'
    return response

if __name__ == '__main__':
    app.run(host='127.0.0.1', port=5000, debug=False)
