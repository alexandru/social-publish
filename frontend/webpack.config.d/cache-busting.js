// Configure cache busting for production builds
const HtmlWebpackPlugin = require('html-webpack-plugin');
const path = require('path');

// Add HtmlWebpackPlugin so development server gets an index.html with
// the correct script tag injected. Only change the output filename to a
// content-hashed name in production builds.
config.output = config.output || {};

if (config.mode === 'production') {
    // Update output filename to include content hash for cache busting
    config.output.filename = (chunkData) => {
        return chunkData.chunk.name === 'main'
            ? "app.[contenthash:8].js"
            : "app-[name].[contenthash:8].js";
    };
} else {
    // Keep deterministic names in development for the dev server
    config.output.filename = (chunkData) => {
        return chunkData.chunk.name === 'main'
            ? "app.js"
            : "app-[name].js";
    };
}

// Ensure plugins array exists and add HtmlWebpackPlugin unconditionally so
// the dev server serves an index.html with the correct injected script.
config.plugins = config.plugins || [];
config.plugins.push(
    new HtmlWebpackPlugin({
        template: path.resolve(__dirname, 'kotlin/index.html'),
        filename: 'index.html',
        inject: 'body',
        scriptLoading: 'blocking'
    })
);
