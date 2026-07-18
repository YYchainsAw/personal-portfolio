import { authApi } from '@/api/authApi'
import { onAuthenticationRequired } from '@/api/http'

import { createSessionStore } from './session'

export const sessionStore = createSessionStore(authApi)

onAuthenticationRequired(() => sessionStore.invalidate())
