export type Result<R, L> =
  { type: 'error'; error: L } | { type: 'success'; result: R }

export const error = <L, R = never>(error: L): Result<R, L> =>
  ({ type: 'error', error })

export const success = <R, L = never>(result: R): Result<R, L> =>
  ({ type: 'success', result })

export const leftMap = <L, L2>(f: (l: L) => L2) => <R>(result: Result<R, L>): Result<R, L2> =>
  result.type === 'error' ? error(f(result.error)) : success(result.result)

export default {
  error,
  success,
  leftMap
}
