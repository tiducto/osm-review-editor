export type Locale = 'cs' | 'en'

export interface PluralForms {
  zero?: string
  one?: string
  two?: string
  few?: string
  many?: string
  other: string
}

export type MessageValue = string | PluralForms

export type Translation<E> = { [K in keyof E]: E[K] extends string ? string : PluralForms }
