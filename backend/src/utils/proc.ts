export type TerminationSignal =
  | 'SIGINT'
  | 'SIGTERM'
  | 'SIGHUP'
  | 'SIGQUIT'

export function waitOnTerminationSignal(): Promise<TerminationSignal> {
  const installSignal = (s: TerminationSignal) => (cb: (s: TerminationSignal) => void) =>
    process.on(s, () => cb(s))

  return new Promise<TerminationSignal>((resolve) => {
    installSignal('SIGINT')(resolve)
    installSignal('SIGTERM')(resolve)
    installSignal('SIGHUP')(resolve)
    installSignal('SIGQUIT')(resolve)
  })
}
