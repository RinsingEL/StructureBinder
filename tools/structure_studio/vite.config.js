import {defineConfig} from 'vite';
export default defineConfig({build: {target: 'es2022',rollupOptions:{input:{studio:'index.html',campus:'campus.html'}}}});
