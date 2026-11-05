import { t } from '@/i18n'

export type AuthorKind = 'user' | 'claude' | 'rule' | 'remote'

export interface AuthorInfo {
  kind: AuthorKind
  label: string
  ruleId: string | null
}

export function parseAuthor(author: string): AuthorInfo {
  if (author.startsWith('rule:')) return { kind: 'rule', label: author.slice(5), ruleId: author.slice(5) }
  switch (author) {
    case 'claude':
      return { kind: 'claude', label: 'Claude', ruleId: null }
    case 'remote':
      return { kind: 'remote', label: t('app.author.remote'), ruleId: null }
    case 'user':
      return { kind: 'user', label: t('app.author.me'), ruleId: null }
    default:
      return { kind: 'user', label: author, ruleId: null }
  }
}
