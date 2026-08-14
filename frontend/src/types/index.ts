export interface User {
  id: string
  username: string
  email?: string | null
  nickname?: string | null
  timezone?: string | null
  birthDate?: string | null
  gender?: string | null
  createdAt?: string | null
}

export interface Place {
  country?: string
  province?: string
  city?: string
}

export interface PersonaTraits {
  warmth?: number
  maturity?: number
  independence?: number
  playfulness?: number
  curiosity?: number
  confidence?: number
  patience?: number
  sociability?: number
  emotionalSensitivity?: number
  rationality?: number
}

export interface PersonaBehavior {
  trigger?: string
  tendencies?: string[]
}

export interface PersonaLifeEvent {
  type?: string
  subtype?: string
  title?: string
  description?: string
  startTime?: string | null
  endTime?: string | null
  importance?: number
  emotionalSignificance?: number
}

export interface Persona {
  identity?: {
    name?: string
    gender?: string
    birthDate?: string
    nationality?: string
    timezone?: string
    birthPlace?: Place
  }
  relationship?: { type?: string }
  personality?: { traits?: PersonaTraits; summary?: string }
  communication?: {
    formality?: number
    verbosity?: number
    emojiUsage?: number
    teasing?: number
    initiative?: number
    directness?: number
    humor?: number
    style?: string
  }
  behaviors?: PersonaBehavior[]
  values?: string[]
  boundaries?: string[]
  life?: {
    background?: string
    events?: PersonaLifeEvent[]
    residences?: { city?: string; startDate?: string; endDate?: string | null }[]
  }
}

export interface Companion {
  id: string
  name: string
  gender?: string | null
  age?: number | null
  birthDate?: string | null
  nextBirthday?: string | null
  birthPlace?: Place | null
  nationality?: string | null
  timezone?: string | null
  greeting?: string | null
  persona?: Persona | null
  relationshipType?: string | null
  relationshipStage?: string | null
  createdAt?: string | null
}

export interface LifeEvent {
  id: string
  type: string
  subtype?: string | null
  title: string
  description?: string | null
  startTime?: string | null
  endTime?: string | null
  importance: number
  emotionalSignificance: number
}

export interface Conversation {
  id: string
  userId: string
  companionId: string
  title: string
  startedAt: string
  lastMessageAt?: string | null
  messageCount: number
  summary?: string | null
  status?: string | null
}

export interface Message {
  id: string
  conversationId: string
  senderType: 'user' | 'companion' | 'system'
  content: string
  intent?: string | null
  emotion?: string | null
  topic?: string | null
  proactive?: boolean
  createdAt: string
}

export interface Memory {
  id: string
  type: 'episodic' | 'semantic' | 'shared'
  content: string
  summary?: string | null
  importance: number
  confidence: number
  emotionalWeight: number
  relationshipWeight: number
  retrievalCount: number
  lastRetrievedAt?: string | null
  occurredAt?: string | null
  status?: string
  sourceType?: string | null
  sourceId?: string | null
  createdAt: string
}

export interface UserFact {
  id: string
  predicate: string
  object?: string | null
  confidence: number
  sourceType?: string
  firstObservedAt?: string
  lastObservedAt?: string
}

export interface UserPreference {
  id: string
  category: string
  preference: string
  confidence: number
  sourceType?: string
}

export interface UserPattern {
  id: string
  pattern: string
  description?: string | null
  confidence: number
  evidenceCount: number
  evidence?: unknown[]
}

export interface UserHypothesis {
  id: string
  hypothesis: string
  description?: string | null
  confidence: number
  evidence?: unknown[]
}

export interface AgentState {
  mood?: string
  energy: number
  stress: number
  socialEnergy: number
  curiosity: number
  emotionalCloseness: number
  updatedAt?: string
}

export interface Relationship {
  id: string
  relationshipType?: string
  relationshipStage: string
  familiarity: number
  trust: number
  intimacy: number
  affection: number
  sharedExperienceCount: number
  messageCount: number
  lastInteractionAt?: string | null
  startedAt: string
}

export interface RelationshipEvent {
  id: string
  type: string
  title: string
  description?: string
  significance: number
  occurredAt: string
}

export interface SharedExperience {
  id: string
  type: string
  title: string
  description?: string
  importance: number
  occurredAt: string
}

export interface Reminder {
  id: string
  type: string
  title: string
  content?: string | null
  remindAt: string
  status: string
}

export interface Notification {
  id: string
  type: string
  title: string
  content?: string | null
  read: boolean
  createdAt: string
}

export interface ReflectionRecord {
  id: string
  type: string
  period: string
  summary?: string
  insights?: unknown[]
  createdAt: string
}

export interface MemorySourceMessage {
  sender: string
  content: string
  createdAt: string
}

export interface MemoryLink {
  id: string
  fromMemoryId: string
  toMemoryId: string
  relation: string
  strength: number
}

export interface PersonaVersion {
  id: string
  version: number
  active: boolean
  changeSource?: string
  changeReason?: string
  createdAt: string
}
