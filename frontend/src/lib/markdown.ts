import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'
import 'highlight.js/styles/github-dark-dimmed.css'

// Local trusted data, but keep raw HTML off; render links to open in a new tab.
// Fenced code blocks get highlight.js colors when the language is known (the common-languages
// bundle — the full set would triple the chunk); unknown/absent languages fall back to
// markdown-it's plain escaping. The theme css only contributes .hljs-* token colors — the app's
// own pre/code background from theme.css stays (markdown-it doesn't add the .hljs class).
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: false,
  highlight: (code, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
      } catch {
        // fall through to plain escaping
      }
    }
    return ''
  },
})

const defaultLinkOpen =
  md.renderer.rules.link_open ||
  ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(src: string | null | undefined): string {
  return md.render(src ?? '')
}
