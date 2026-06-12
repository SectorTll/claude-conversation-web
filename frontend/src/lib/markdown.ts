import MarkdownIt from 'markdown-it'

// Local trusted data, but keep raw HTML off; render links to open in a new tab.
const md = new MarkdownIt({ html: false, linkify: true, breaks: false })

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
