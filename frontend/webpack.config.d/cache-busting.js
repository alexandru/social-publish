// Configure cache busting for production builds
const HtmlWebpackPlugin = require('html-webpack-plugin');
const path = require('path');

// Only apply cache busting in production mode
if (config.mode === 'production') {
    // Ensure config.output exists
    config.output = config.output || {};
    
    // Update output filename to include content hash
    config.output.filename = (chunkData) => {
        return chunkData.chunk.name === 'main'
            ? "app.[contenthash:8].js"
            : "app-[name].[contenthash:8].js";
    };

    // Ensure plugins array exists
    config.plugins = config.plugins || [];
    
    // Add HtmlWebpackPlugin to generate index.html with proper script tags
    config.plugins.push(
        new HtmlWebpackPlugin({
            template: path.resolve(__dirname, 'kotlin/index.html'),
            filename: 'index.html',
            inject: 'body',
            scriptLoading: 'blocking'
        })
    );
}
