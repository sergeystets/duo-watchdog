import * as http from 'http';
import * as https from 'https';
import { Response, Transport } from './core/client';

/**
 * GitLab through Node's http and https modules, which VS Code patches with its proxy settings (http.proxy) and the
 * system's trusted certificates (http.systemCertificates); the global fetch is not patched the same way.
 */
export const nodeTransport: Transport = {
    post(endpoint, token, jsonBody) {
        return send(endpoint, 'POST', token, jsonBody);
    },
    get(uri, token) {
        return send(uri, 'GET', token, null);
    },
};

function send(uri: URL, method: 'GET' | 'POST', token: string, jsonBody: string | null): Promise<Response> {
    return new Promise((resolve, reject) => {
        const client = uri.protocol === 'http:' ? http : https;
        const headers: Record<string, string | number> = {
            'Authorization': 'Bearer ' + token,
            'User-Agent': 'duo-watchdog-vscode',
        };
        if (jsonBody != null) {
            headers['Content-Type'] = 'application/json';
            headers['Content-Length'] = Buffer.byteLength(jsonBody);
        }
        const request = client.request(uri, { method, timeout: 30_000, headers }, response => {
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
        request.end(jsonBody ?? undefined);
    });
}
