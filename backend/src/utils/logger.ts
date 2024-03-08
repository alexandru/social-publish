const prefix = () => `[${new Date().toISOString()}]`

export default {
  debug: (message: string, ...args: unknown[]) =>
    console.debug(`${prefix()} [DEBUG] ${message}`, ...args),
  info: (message: string, ...args: unknown[]) =>
    console.info(`${prefix()} [INFO] ${message}`, ...args),
  warn: (message: string, ...args: unknown[]) =>
    console.warn(`${prefix()} [WARN] ${message}`, ...args),
  error: (message: string, ...args: unknown[]) =>
    console.error(`${prefix()} [ERROR] ${message}`, ...args)
}
