import { createContext, useContext } from 'react'
import type { Session } from './api'

export const SessionContext = createContext<{ session: Session | null; refresh: () => Promise<void> }>({
  session: null,
  refresh: async () => {},
})

export const useSession = () => useContext(SessionContext)
