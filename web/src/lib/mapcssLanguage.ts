import { StreamLanguage, type StreamParser } from '@codemirror/language'

interface State {
  inBlock: boolean
  inSelector: boolean
  inComment: boolean
  afterColon: boolean
}

const parser: StreamParser<State> = {
  name: 'mapcss',
  startState: () => ({ inBlock: false, inSelector: false, inComment: false, afterColon: false }),
  token(stream, state) {
    if (state.inComment) {
      if (stream.skipTo('*/')) {
        stream.match('*/')
        state.inComment = false
      } else {
        stream.skipToEnd()
      }
      return 'comment'
    }
    if (stream.eatSpace()) return null
    if (stream.match('/*')) {
      state.inComment = true
      return 'comment'
    }
    if (stream.match('//')) {
      stream.skipToEnd()
      return 'comment'
    }
    const ch = stream.peek()
    if (ch === '"' || ch === "'") {
      const q = stream.next()
      let escaped = false
      let c: string | void
      while ((c = stream.next()) !== undefined) {
        if (c === q && !escaped) break
        escaped = !escaped && c === '\\'
      }
      return 'string'
    }
    if (state.inBlock) {
      if (ch === '}') {
        stream.next()
        state.inBlock = false
        state.afterColon = false
        return 'brace'
      }
      if (ch === ';') {
        stream.next()
        state.afterColon = false
        return 'punctuation'
      }
      if (ch === ':') {
        stream.next()
        state.afterColon = true
        return 'punctuation'
      }
      if (!state.afterColon) {
        if (stream.match(/^set\b/)) return 'keyword'
        if (stream.match(/^-?[A-Za-z][\w-]*/)) return 'propertyName'
        stream.next()
        return null
      }
      if (stream.match(/^#[0-9a-fA-F]{3,8}\b/)) return 'color'
      if (stream.match(/^-?\d+(\.\d+)?/)) return 'number'
      if (stream.match(/^(true|false|yes|no)\b/)) return 'bool'
      if (stream.match(/^[A-Za-z_][\w:-]*(?=\s*\()/)) return 'macroName'
      if (stream.match(/^[A-Za-z_][\w:-]*/)) return 'atom'
      stream.next()
      return 'operator'
    }
    if (ch === '{') {
      stream.next()
      state.inBlock = true
      state.afterColon = false
      return 'brace'
    }
    if (ch === '[') {
      stream.next()
      state.inSelector = true
      return 'squareBracket'
    }
    if (ch === ']') {
      stream.next()
      state.inSelector = false
      return 'squareBracket'
    }
    if (state.inSelector) {
      if (stream.match(/^\/(\\.|[^/\\])*\/[a-z]*/)) return 'regexp'
      if (stream.match(/^(=~|!~|!=|\^=|\$=|\*=|~=|<=|>=|=|<|>|!|\?!|\?)/)) return 'operator'
      if (stream.match(/^-?\d+(\.\d+)?(?=\s*\])/)) return 'number'
      if (stream.match(/^[^\]=!~<>^$*?"'\s]+/)) return 'attributeName'
      stream.next()
      return null
    }
    if (stream.match(/^(node|way|relation|area|canvas|meta|\*)\b/) || stream.match('*')) return 'typeName'
    if (stream.match(/^\.[\w-]+/)) return 'className'
    if (stream.match(/^!?:[\w-]+/)) return 'labelName'
    if (stream.match(/^::[\w-]+/)) return 'labelName'
    if (stream.match(/^\|z[\d-]+/)) return 'number'
    if (stream.match(/^[>,+⧉∈]/)) return 'operator'
    stream.next()
    return null
  },
  languageData: { commentTokens: { line: '//', block: { open: '/*', close: '*/' } } },
}

export const mapcss = StreamLanguage.define(parser)
