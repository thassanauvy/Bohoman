// Keep the browser's API requests on the frontend origin during development.
config.devServer = {
    ...config.devServer,
    port: 8081,
    proxy: [{ context: ['/api'], target: 'http://127.0.0.1:8080', changeOrigin: false }]
};
