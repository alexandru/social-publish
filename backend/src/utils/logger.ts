const prefix = () => `[${new Date().toISOString()}]`

export default {
  debug: (message: string, ...args: unknown[]) =>
    console.debug(`${prefix()} ${message}`, ...args),
  info: (message: string, ...args: unknown[]) =>
    console.info(`${prefix()} ${message}`, ...args),
  warn: (message: string, ...args: unknown[]) =>
    console.warn(`${prefix()} ${message}`, ...args),
  error: (message: string, ...args: unknown[]) =>
    console.error(`${prefix()} ${message}`, ...args),
}
