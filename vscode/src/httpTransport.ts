import * as http from 'http';
import * as https from 'https';
import { Transport } from './core/client';

/**
 * GitLab through Node's http and https modules, which VS Code patches with its proxy settings (http.proxy) and the
 * system's trusted certificates (http.systemCertificates); the global fetch is not patched the same way.
 */
export const nodeTransport: Transport = {
    post(endpoint, token, jsonBody) {
        return new Promise((resolve, reject) => {
            const client = endpoint.protocol === 'http:' ? http : https;
            const request = client.request(endpoint, {
                method: 'POST',
                timeout: 30_000,
                headers: {
                    'Authorization': 'Bearer ' + token,
                    'Content-Type': 'application/json',
                    'Content-Length': Buffer.byteLength(jsonBody),
                    'User-Agent': 'duo-watchdog-vscode',
                },
            }, response => {
                const chunks: Buffer[] = [];
                response.on('data', (chunk: Buffer) => chunks.push(chunk));
                response.on('end', () => resolve({
                    status: response.statusCode ?? 0,
                    body: Buffer.concat(chunks).toString('utf8'),
                }));
                response.on('error', reject);
            });
            request.on('timeout', () => request.destroy(new Error('request timed out')));
            request.on('error', reject);
            request.end(jsonBody);
        });
    },
};
