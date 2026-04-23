import path from 'node:path';
import type { Plugin, Connect } from 'vite';
import {
  buildTree,
  getContent,
  buildCodeTree,
  getCodeContent,
  searchCodeFiles,
  buildCommits,
} from './docs-data.ts';

function json(res: Connect.ServerResponse, data: unknown, status = 200) {
  res.statusCode = status;
  res.setHeader('Content-Type', 'application/json');
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.end(JSON.stringify(data));
}

export function docsPlugin(): Plugin {
  return {
    name: 'docs-api',
    configureServer(server) {
      server.middlewares.use('/api/docs', (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost');

        if (url.pathname === '/tree') {
          return json(res, { sections: buildTree() });
        }

        if (url.pathname === '/content') {
          const filePath = url.searchParams.get('path');
          if (!filePath) return json(res, { error: 'path required' }, 400);
          const content = getContent(filePath);
          if (content === null) return json(res, { error: 'not found' }, 404);
          return json(res, { content });
        }

        next();
      });

      server.middlewares.use('/api/commits', (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost');
        if (url.pathname === '/' || url.pathname === '') {
          const limit = parseInt(url.searchParams.get('limit') ?? '60', 10);
          return json(res, { commits: buildCommits(Math.min(Math.max(limit, 1), 200)) });
        }
        next();
      });

      server.middlewares.use('/api/code', (req, res, next) => {
        const url = new URL(req.url ?? '/', 'http://localhost');

        if (url.pathname === '/tree') {
          return json(res, { dirs: buildCodeTree() });
        }

        if (url.pathname === '/content') {
          const filePath = url.searchParams.get('path');
          if (!filePath) return json(res, { error: 'path required' }, 400);
          const content = getCodeContent(filePath);
          if (content === null) return json(res, { error: 'not found' }, 404);
          const ext = path.extname(filePath).slice(1);
          return json(res, { content, ext, path: filePath });
        }

        if (url.pathname === '/search') {
          const q = (url.searchParams.get('q') ?? '').trim();
          if (q.length < 2) return json(res, { results: [] });
          const isRegex = url.searchParams.get('regex') === '1';
          return json(res, { results: searchCodeFiles(q, isRegex) });
        }

        next();
      });
    },
  };
}
