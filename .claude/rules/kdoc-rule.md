---
paths:
  - "**/*.{kt,kts}"
---

# KDoc Style Guide

## Core Rules

| Rule | Description | Example |
|------|-------------|---------|
| Language | English throughout | `Returns the current selection state.` |
| Tone | Declarative, neutral — no polite or passive constructions | ❌ `This method returns` → ✅ `Returns` |
| Endings | Declarative sentence or noun phrase, with period | `Converts upstream operations.` |
| Annotation order | KDoc → annotations → declaration | KDoc must appear above `@Stable`, `@JvmOverloads` |
| Tags | `@param`, `@return`, `@throws` in same declarative style | `@param dispatcher Dispatcher used for Yorkie operations.` |
| Class references | Brackets, not backticks | ❌ `` `Client` `` → ✅ `[Client]` |

## Checklist

- [ ] Written in English
- [ ] No passive or polite constructions ("This returns", "You can use", "Please note")
- [ ] Ends with a period
- [ ] KDoc placed above annotations, not between annotation and declaration
- [ ] Class/function references use `[ClassName]`, not backticks
