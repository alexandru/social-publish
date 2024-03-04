import { defineConfig } from 'vite';
import preact from '@preact/preset-vite';

// https://vitejs.dev/config/
export default defineConfig({
	plugins: [preact()],
	server: {
		port: 3001,
		proxy: {
			'/rss': {
				target: 'http://localhost:3000',
				changeOrigin: true,
				rewrite: (path) => path
			},
			'/api': {
				target: 'http://localhost:3000',
				changeOrigin: true,
				rewrite: (path) => path
			}
		}
	}
});
