import { onScopeDispose, ref } from 'vue'

export function useMedia(query) {
  const mql = window.matchMedia(query)
  const matches = ref(mql.matches)
  const onChange = (e) => (matches.value = e.matches)
  mql.addEventListener('change', onChange)
  onScopeDispose(() => mql.removeEventListener('change', onChange))
  return matches
}
