import type { ContextFill, TaskItem } from '../types'

const FULL = '█'
const EMPTY = '░'

/** Context fill at or above this percentage draws red, as auto-compact nears. */
const DANGER_PERCENT = 80
const WARN_PERCENT = 60

export type Bar = { filled: string; empty: string }

/** A bar `cells` wide, filled to `fraction` (clamped to 0..1) in whole cells. */
export function bar(fraction: number, cells: number): Bar {
  const width = Math.max(1, Math.floor(cells))
  const clamped = Number.isFinite(fraction) ? Math.min(1, Math.max(0, fraction)) : 0
  const filled = Math.round(clamped * width)

  return { filled: FULL.repeat(filled), empty: EMPTY.repeat(width - filled) }
}

/** `950`, `82k`, `1M`, `1.5M`. */
export function formatTokens(tokens: number): string {
  if (tokens >= 1_000_000) {
    const millions = tokens / 1_000_000

    return `${Number.isInteger(millions) ? millions : millions.toFixed(1)}M`
  }
  if (tokens >= 1000) {
    return `${Math.round(tokens / 1000)}k`
  }

  return String(tokens)
}

export function contextColor(percent: number): 'green' | 'yellow' | 'red' {
  if (percent >= DANGER_PERCENT) {
    return 'red'
  }

  return percent >= WARN_PERCENT ? 'yellow' : 'green'
}

export function contextFraction(fill: ContextFill): number {
  return fill.window > 0 ? fill.tokens / fill.window : fill.percent / 100
}

export type TaskSummary = { done: number; total: number; current?: string }

/** Done and total, and what is running now (or comes next); null with no tasks. */
export function summarizeTasks(list: readonly TaskItem[]): TaskSummary | null {
  if (list.length === 0) {
    return null
  }
  const done = list.filter(task => task.status === 'completed').length
  const running = list.find(task => task.status === 'in_progress')
  const pending = list.find(task => task.status === 'pending')
  const current =
    running !== undefined
      ? (running.activeForm ?? running.subject)
      : pending !== undefined
        ? `next: ${pending.subject}`
        : undefined

  return current === undefined ? { done, total: list.length } : { done, total: list.length, current }
}

export function withCreated(
  list: readonly TaskItem[],
  task: { id: string; subject: string },
  activeForm: string | undefined,
): TaskItem[] {
  const created: TaskItem = {
    id: task.id,
    subject: task.subject,
    status: 'pending',
    ...(activeForm !== undefined && { activeForm }),
  }

  return [...list.filter(one => one.id !== task.id), created]
}

export type TaskChange = {
  taskId: string
  subject?: string
  activeForm?: string
  status?: TaskItem['status'] | 'deleted'
}

/** Applies a TaskUpdate; a task this list never saw is added under its id. */
export function withUpdate(list: readonly TaskItem[], change: TaskChange): TaskItem[] {
  if (change.status === 'deleted') {
    return list.filter(one => one.id !== change.taskId)
  }
  const known = list.find(one => one.id === change.taskId) ?? {
    id: change.taskId,
    subject: change.subject ?? `Task ${change.taskId}`,
    status: 'pending' as const,
  }
  const updated: TaskItem = {
    ...known,
    ...(change.subject !== undefined && { subject: change.subject }),
    ...(change.activeForm !== undefined && { activeForm: change.activeForm }),
    ...(change.status !== undefined && { status: change.status }),
  }
  const isKnown = list.some(one => one.id === change.taskId)

  return isKnown ? list.map(one => (one.id === change.taskId ? updated : one)) : [...list, updated]
}

/** The list as TaskList reported it, keeping the spinner text this list knew. */
export function fromTaskList(
  list: readonly TaskItem[],
  reported: readonly { id: string; subject: string; status: TaskItem['status'] }[],
): TaskItem[] {
  return reported.map(task => {
    const activeForm = list.find(one => one.id === task.id)?.activeForm

    return {
      id: task.id,
      subject: task.subject,
      status: task.status,
      ...(activeForm !== undefined && { activeForm }),
    }
  })
}

export function fromTodos(
  todos: readonly { content: string; status: TaskItem['status']; activeForm: string }[],
): TaskItem[] {
  return todos.map((todo, index) => ({
    id: `todo-${index + 1}`,
    subject: todo.content,
    status: todo.status,
    activeForm: todo.activeForm,
  }))
}

/** True when two lists draw the same, so an unchanged write is skipped. */
export function isSameTasks(a: readonly TaskItem[], b: readonly TaskItem[]): boolean {
  return (
    a.length === b.length &&
    a.every((task, index) => {
      const other = b[index]

      return (
        other !== undefined &&
        task.id === other.id &&
        task.subject === other.subject &&
        task.status === other.status &&
        task.activeForm === other.activeForm
      )
    })
  )
}

export function isSameContext(a: ContextFill | null, b: ContextFill | null): boolean {
  if (a === null || b === null) {
    return a === b
  }

  return a.tokens === b.tokens && a.window === b.window && a.percent === b.percent
}

/** One plain-text line of both bars: the status-line fallback and `/progress`. */
export function plainLines(fill: ContextFill | null, tasks: TaskSummary | null, cells: number): string[] {
  const lines: string[] = []
  if (fill !== null) {
    const { filled, empty } = bar(contextFraction(fill), cells)
    lines.push(
      `Context ${filled}${empty} ${fill.percent}% · ${formatTokens(fill.tokens)}/${formatTokens(fill.window)}`,
    )
  }
  if (tasks !== null) {
    const { filled, empty } = bar(tasks.done / tasks.total, cells)
    const current = tasks.current === undefined ? '' : ` · ${tasks.current}`
    lines.push(`Tasks   ${filled}${empty} ${tasks.done}/${tasks.total}${current}`)
  }

  return lines
}
