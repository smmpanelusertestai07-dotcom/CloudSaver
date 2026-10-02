/** The context window's fill as of the last model response. */
export type ContextFill = { tokens: number; window: number; percent: number }

/** One entry of the session's task list (TaskCreate / TodoWrite). */
export type TaskItem = {
  id: string
  subject: string
  status: 'pending' | 'in_progress' | 'completed'
  activeForm?: string
}

declare module 'claude-code' {
  interface PluginState {
    'prompt-progress': {
      context: ContextFill | null
      tasks: TaskItem[]
      isHidden: boolean
    }
  }
}
