if (config.devServer) {
  config.devServer.historyApiFallback = true
}

if (config.resolve) {
  config.resolve.mainFields = ['main', 'module']
  config.resolve.alias = {
    ...(config.resolve.alias || {}),
    'tiny-warning': require.resolve('tiny-warning/dist/tiny-warning.cjs.js')
  }
}
