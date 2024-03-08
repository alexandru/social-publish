import crypto from 'crypto'
import fs from 'fs'

export type HashAlgorithm = 'md5' | 'sha256' | 'sha512'

export const readFileAsUint8Array = (mimetype: string, filePath: string): Promise<Uint8Array> => {
  return new Promise((resolve, reject) => {
    fs.readFile(filePath, (error, data) => {
      if (error) {
        reject(error)
      } else {
        const base64 = data.toString('base64')
        const encoder = new TextEncoder()
        resolve(encoder.encode(`data:${mimetype};base64,${base64}`))
      }
    })
  })
}

export const calculateFileHash = (algorithm: HashAlgorithm, filePath: string): Promise<string> => {
  return new Promise((resolve, reject) => {
    const hash = crypto.createHash(algorithm)
    const stream = fs.createReadStream(filePath)

    stream.on('data', (data) => {
      hash.update(data)
    })

    stream.on('end', () => {
      resolve(hash.digest('hex'))
    })

    stream.on('error', (error) => {
      reject(error)
    })
  })
}
