const http = require('http');
const url = require('url');
const port = parseInt(process.argv[2], 10) || 5678;

const requestHandler = (request, response) => {
    const url_parts = url.parse(request.url, true);

    const call = url_parts.query.call;

    if (call === undefined) {
        console.log('health check');
        response.end('ok');
        return;
    }

    console.log('Calling', call);
    try {
        http.get(url_parts.query.call, (res) => {
            res.on('data', () => {});
        res.on('end', () => {
            response.end('Got response');
    });
    }).on('error', (e) => {
            console.error('error', e.message);
        response.statusCode = 500;
        response.end('There was a problem');
    });
    } catch (e) {
        console.error('error', e.message);
        response.statusCode = 500;
        response.end('There was a problem');
    }
};

const server = http.createServer(requestHandler);

server.listen(port, '0.0.0.0', (err) => {
    if (err) {
        return console.log('something bad happened', err);
    }

    console.log('server is listening on', port);
});
