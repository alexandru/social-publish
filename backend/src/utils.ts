import { convert } from "html-to-text"

function convertHtml(html: string): string {
    const text = convert(html.trim(), {
        wordwrap: false,
        formatters: {
            "forHref": (elem, walk, builder, formatOptions) => {
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

export default {
    convertHtml
}
