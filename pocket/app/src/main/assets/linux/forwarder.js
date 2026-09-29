// PocketIDE's port forwarder: lets a program's own web page open inside code-server the way it
// does on a computer. code-server shows such a page from http://<port>.localhost:<this port>/,
// and this passes each request, and each WebSocket, to 127.0.0.1:<port> as if it had been
// addressed to localhost:<port>, which is what programs like Antigravity's agent accept.
//
// The page's own origin is presented as http://localhost:<port> too, because such programs
// refuse calls from any other origin. Only that exact origin is changed: a call from anywhere
// else keeps its own, so the program still refuses it as it would on a computer.
//
// Usage: node forwarder.js <listen port>   (prints "ready" once it listens, on 127.0.0.1 only)
'use strict';

const http = require('http');
const net = require('net');

const listenPort = Number.parseInt(process.argv[2], 10);
if (!Number.isInteger(listenPort) || listenPort < 1024 || listenPort > 65535) {
  process.stderr.write('usage: forwarder.js <listen port>\n');
  process.exit(2);
}

// Only <port>.localhost, with a port a program may use, and never this forwarder itself.
function targetOf(host) {
  const match = /^(\d{4,5})\.localhost(:\d{1,5})?$/i.exec(host || '');
  if (!match) return null;
  const port = Number.parseInt(match[1], 10);
  return port >= 1024 && port <= 65535 && port !== listenPort ? port : null;
}

// The headers a request keeps, with Host, and the page's own Origin and Referer, as localhost:<port>.
function rewritten(name, value, port, host) {
  const lower = name.toLowerCase();
  const own = `http://${host}`;
  if (lower === 'host') return `localhost:${port}`;
  if ((lower === 'origin' || lower === 'referer') && typeof value === 'string' && (value === own || value.startsWith(own + '/'))) {
    return `http://localhost:${port}` + value.slice(own.length);
  }
  return value;
}

const server = http.createServer((request, response) => {
  const port = targetOf(request.headers.host);
  if (!port) {
    response.writeHead(404, { 'content-type': 'text/plain' });
    response.end('Not a forwarded address.\n');
    return;
  }
  const headers = {};
  for (const [name, value] of Object.entries(request.headers)) headers[name] = rewritten(name, value, port, request.headers.host);
  const upstream = http.request({ host: '127.0.0.1', port, method: request.method, path: request.url, headers }, (answer) => {
    response.writeHead(answer.statusCode || 502, answer.headers);
    answer.pipe(response);
  });
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502, { 'content-type': 'text/plain' });
    response.end('The program on this port is not answering.\n');
  });
  request.pipe(upstream);
});

server.on('upgrade', (request, socket, head) => {
  const port = targetOf(request.headers.host);
  if (!port) {
    socket.destroy();
    return;
  }
  const upstream = net.connect(port, '127.0.0.1', () => {
    const lines = [`${request.method} ${request.url} HTTP/${request.httpVersion}`];
    for (let i = 0; i < request.rawHeaders.length; i += 2) {
      const name = request.rawHeaders[i];
      lines.push(`${name}: ${rewritten(name, request.rawHeaders[i + 1], port, request.headers.host)}`);
    }
    upstream.write(lines.join('\r\n') + '\r\n\r\n');
    if (head && head.length) upstream.write(head);
    upstream.pipe(socket);
    socket.pipe(upstream);
  });
  upstream.on('error', () => socket.destroy());
  socket.on('error', () => upstream.destroy());
});

server.on('clientError', (error, socket) => socket.destroy());
server.listen(listenPort, '127.0.0.1', () => process.stdout.write('ready\n'));
