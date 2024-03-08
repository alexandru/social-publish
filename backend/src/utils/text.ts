import { convert } from 'html-to-text'

export const convertHtml = (html: string): string => {
  const text = convert(html.trim(), {
    wordwrap: false,
    formatters: {
      forHref: (elem, walk, builder, formatOptions) => {
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

export default {
  convertHtml,
  convertTextToJsonOrNull
}
