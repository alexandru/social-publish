export type Result<R, L> =
  { type: 'error'; error: L } | { type: 'success'; result: R }

export const error = <L, R = never>(error: L): Result<R, L> =>
  ({ type: 'error', error })

export const success = <R, L = never>(result: R): Result<R, L> =>
  ({ type: 'success', result })

export default {
  error,
  success
}
