import { convert } from 'html-to-text'

export const convertHtml = (html: string): string => {
  const text = convert(html.trim(), {
    wordwrap: false,
    formatters: {
      forHref: (elem, walk, builder /*,formatOptions*/) => {
        // const href = elem.attribs?.href as string | undefined
        walk(elem.children, builder)
      }
    },
    selectors: [
      {
        selector: 'a',
        format: 'forHref'
      }
    ]
  })
  return text.trim()
}

export const convertTextToJsonOrNull = (text: string): unknown | null => {
  try {
    return JSON.parse(text)
  } catch (_e) {
    return null
  }
}

/**
 * cyrb53 (c) 2018 bryc (github.com/bryc)
 */
export const hashCode = (str: string, seed: number = 0) => {
  let h1 = 0xdeadbeef ^ seed
  let h2 = 0x41c6ce57 ^ seed
  for (let i = 0, ch; i < str.length; i++) {
    ch = str.charCodeAt(i)
    h1 = Math.imul(h1 ^ ch, 2654435761)
    h2 = Math.imul(h2 ^ ch, 1597334677)
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507)
  h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909)
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507)
  h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909)
  return 4294967296 * (2097151 & h2) + (h1 >>> 0)
}

export const hashCodeMod = (str: string, mod: number) => {
  const h = hashCode(str)
  const r = ((h % mod) + mod) % mod
  if (Number.isNaN(r) || r < 0 || r >= mod) {
    throw new Error(`hashCodeMod(${str}, ${mod}) returned invalid result: ${r}`)
  }
  return r
}

export default {
  convertHtml,
  convertTextToJsonOrNull
}
