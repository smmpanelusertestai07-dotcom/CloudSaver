/**
 * prompt-progress: progress bars at the Claude Code prompt.
 *
 *   Context ████████░░░░░░░░░░░░░░░░░░░░░░░░ 25% · 250k/1M tokens
 *   Tasks   ████████████████░░░░░░░░░░░░░░░░ 2/4 · Writing tests
 *
 * Context is the model's context window as of the last response (yellow from
 * 60%, red from 80%). Tasks is the session's TaskCreate/TaskUpdate or TodoWrite
 * list and what is running now; a list that is all done goes with the next
 * prompt. The terminal and Claude Code Desktop draw the bars in the band above
 * the prompt (the AbovePrompt site, theirs alone today); with neither attached
 * they go out as this plugin's status line. `/progress` prints them anywhere;
 * `/progress off` and `/progress on` hide and show them.
 *
 * Nothing loads .claude/mods by itself. For one session, copy this folder into
 * ~/.claude/dev-mods/<session id>/ and enable hot reloading when Claude Code
 * asks; locally, `claude --plugin-dir .claude/mods/prompt-progress`; in every
 * session on this repository, move it to .claude/skills/prompt-progress, where
 * it loads once the workspace is trusted. `claude plugin validate` and
 * `claude plugin test` on the folder check it. Built against Claude Code
 * 2.1.287; the mods API is early access and can change between releases.
 */
import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register, RenderSurface } from 'claude-code'

import type { ContextFill, TaskItem } from '../types'
import {
  bar,
  contextColor,
  contextFraction,
  formatTokens,
  fromTaskList,
  fromTodos,
  isSameContext,
  isSameTasks,
  plainLines,
  summarizeTasks,
  withCreated,
  withUpdate,
} from './bars'

const contextFill = atom({ plugin: 'prompt-progress', key: 'context' } as const, null)
const taskList = atom({ plugin: 'prompt-progress', key: 'tasks' } as const, [])
const isHidden = atom({ plugin: 'prompt-progress', key: 'isHidden' } as const, false)

/** The surfaces that draw the AbovePrompt band; anywhere else the bars ride the status line. */
const BAND_SURFACES: readonly RenderSurface[] = ['terminal', 'desktop']
const LABEL_CELLS = 8
const SUFFIX_CELLS = 22
const STATUS_CELLS = 12
const COMMAND_CELLS = 24

/** Named ANSI colors follow the terminal's theme; the desktop draws CSS, so it gets hues that read on light and dark. */
const COLORS = {
  terminal: { green: 'green', yellow: 'yellow', red: 'red', tasks: 'cyan' },
  remote: { green: '#2da44e', yellow: '#c69026', red: '#e5534b', tasks: '#3b82f6' },
} as const

// Null until the first push, so a reload always re-sends (or clears) the line.
let lastStatus: string | undefined | null = null

async function refreshContext($: EngineInterface): Promise<void> {
  const { context } = await $.session.usage()
  const fill: ContextFill | null =
    context.tokens === undefined || context.percent === undefined
      ? null
      : { tokens: context.tokens, window: context.window, percent: context.percent }

  if (!isSameContext(await read($, contextFill), fill)) {
    await update($, contextFill, () => fill)
  }
}

async function drawsBand($: EngineInterface): Promise<boolean> {
  const surfaces = await $.session.surfaces()

  return surfaces.some(surface => BAND_SURFACES.includes(surface))
}

/** Pins the bars as this plugin's status line while no attached surface draws the band. */
async function pushStatus($: EngineInterface): Promise<void> {
  const isQuiet = (await read($, isHidden)) || (await drawsBand($))
  const lines = isQuiet
    ? []
    : plainLines(await read($, contextFill), summarizeTasks(await read($, taskList)), STATUS_CELLS)
  const text = lines.length === 0 ? undefined : lines.join('   ')

  if (text !== lastStatus) {
    lastStatus = text
    $.ui.status(text)
  }
}

/**
 * Re-reads the window and re-pins the line. Runs after a tool or turn has
 * already happened, so a failure here only leaves the bars as they were.
 */
async function settle($: EngineInterface): Promise<void> {
  try {
    await refreshContext($)
    await pushStatus($)
  } catch {
    // The bars keep their last reading until the next event.
  }
}

/**
 * Applies a task tool's effect to the list. `change` reads the tool's result,
 * so it runs inside the guard: a result of an unexpected shape never fails the
 * call it follows.
 */
async function changeTasks($: EngineInterface, change: (list: TaskItem[]) => TaskItem[]): Promise<void> {
  try {
    const before = await read($, taskList)

    if (!isSameTasks(before, change(before))) {
      await update($, taskList, list => change(list))
    }
  } catch {
    // A missed change shows until the model's next task call or TaskList.
  }
}

/** A list that is all done is retired when the person moves on. */
async function retireFinished($: EngineInterface): Promise<void> {
  try {
    const list = await read($, taskList)

    if (list.length > 0 && list.every(task => task.status === 'completed')) {
      await update($, taskList, () => [])
      await pushStatus($)
    }
  } catch {
    // The finished list stays until the next task change.
  }
}

async function forget($: EngineInterface): Promise<void> {
  try {
    await update($, taskList, () => [])
    await update($, contextFill, () => null)
    await pushStatus($)
  } catch {
    // A cleared session's next readings replace whatever stayed.
  }
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.command.register({
      name: 'progress',
      description: 'Progress bars at the prompt: show the figures, or turn the bars on or off',
      argumentHint: '[on|off]',
    })
    await settle($)

    return next(e)
  })

  on('session.attach', async ($, e, next) => {
    const attached = await next(e)
    await settle($)

    return attached
  })

  on('session.detach', async ($, e, next) => {
    const detached = await next(e)
    await settle($)

    return detached
  })

  on('session.end', async ($, e, next) => {
    if (e.reason === 'clear') {
      await forget($)
    }

    return next(e)
  })

  on('session.compact', async ($, e, next) => {
    const compacted = await next(e)
    await settle($)

    return compacted
  })

  on('prompt.submit', async ($, e, next) => {
    if (e.turnId === undefined) {
      await retireFinished($)
    }

    return next(e)
  })

  on('turn.complete', async ($, e, next) => {
    const completed = await next(e)

    if (e.agentId === undefined) {
      await settle($)
    }

    return completed
  })

  // Outermost of the tool hooks: it reads the window and pins the line after
  // the task hooks below have recorded the call.
  on('tool.call', async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined) {
      await settle($)
    }

    return ran
  })

  on('tool.call', { tool: 'TaskCreate' }, async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined && ran.deny === undefined && ran.isError !== true) {
      const { result } = ran
      await changeTasks($, list => withCreated(list, result.task, e.activeForm))
    }

    return ran
  })

  on('tool.call', { tool: 'TaskUpdate' }, async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined && ran.deny === undefined && ran.isError !== true) {
      const { result } = ran
      const change = { taskId: e.taskId, subject: e.subject, activeForm: e.activeForm, status: e.status }
      await changeTasks($, list => (result.success ? withUpdate(list, change) : list))
    }

    return ran
  })

  on('tool.call', { tool: 'TaskGet' }, async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined && ran.deny === undefined && ran.isError !== true) {
      const { result } = ran
      await changeTasks($, list =>
        result.task === null
          ? list
          : withUpdate(list, { taskId: result.task.id, subject: result.task.subject, status: result.task.status }),
      )
    }

    return ran
  })

  on('tool.call', { tool: 'TaskList' }, async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined && ran.deny === undefined && ran.isError !== true) {
      const { result } = ran
      await changeTasks($, list => fromTaskList(list, result.tasks))
    }

    return ran
  })

  on('tool.call', { tool: 'TodoWrite' }, async ($, e, next) => {
    const ran = await next(e)

    if (e.agentId === undefined && ran.deny === undefined && ran.isError !== true) {
      await changeTasks($, () => fromTodos(e.todos))
    }

    return ran
  })

  on('command.run', { command: 'progress' }, async ($, e) => {
    const arg = e.args.trim().toLowerCase()

    if (arg === 'off' || arg === 'hide') {
      await update($, isHidden, () => true)
      await settle($)

      return { text: 'Progress bars off. /progress on brings them back.' }
    }
    if (arg === 'on' || arg === 'show') {
      await update($, isHidden, () => false)
    }
    await settle($)

    const lines = plainLines(await read($, contextFill), summarizeTasks(await read($, taskList)), COMMAND_CELLS)
    const figures =
      lines.length > 0
        ? lines
        : ['No readings yet: the bars fill in after the next model response or task change.']
    const surfaces = await $.session.surfaces()
    const banded = surfaces.filter(surface => BAND_SURFACES.includes(surface))
    const attached = surfaces.length > 0 ? surfaces.join(', ') : 'none'
    const where =
      banded.length > 0
        ? `Drawn above the prompt on: ${banded.join(', ')}.`
        : `No attached surface draws the band above the prompt (attached: ${attached}); the bars go out as this plugin's status line instead.`
    const state = (await read($, isHidden)) ? 'Progress bars are off; /progress on shows them.' : where

    return { text: [...figures, state].join('\n') }
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    if (e.props.hasSurvey || (await read($, isHidden))) {
      return next(e)
    }
    const fill = await read($, contextFill)
    const tasks = summarizeTasks(await read($, taskList))

    if (fill === null && tasks === null) {
      return next(e)
    }

    const { Box, Text } = $.ui.resolve(e)
    const colors = e.surface === 'terminal' ? COLORS.terminal : COLORS.remote
    const cells = Math.max(8, Math.min(32, e.props.bodyColumns - LABEL_CELLS - SUFFIX_CELLS))
    const contextBar = fill === null ? null : bar(contextFraction(fill), cells)
    const taskBar = tasks === null ? null : bar(tasks.done / tasks.total, cells)
    const isAllDone = tasks !== null && tasks.done === tasks.total

    return (
      <Box flexDirection="column">
        {fill !== null && contextBar !== null && (
          <Box flexDirection="row">
            <Text dimColor>{'Context '}</Text>
            <Text color={colors[contextColor(fill.percent)]}>{contextBar.filled}</Text>
            <Text dimColor>{contextBar.empty}</Text>
            <Text bold>{` ${fill.percent}%`}</Text>
            <Text dimColor wrap="truncate-end">
              {` · ${formatTokens(fill.tokens)}/${formatTokens(fill.window)} tokens`}
            </Text>
          </Box>
        )}
        {tasks !== null && taskBar !== null && (
          <Box flexDirection="row">
            <Text dimColor>{'Tasks   '}</Text>
            <Text color={isAllDone ? colors.green : colors.tasks}>{taskBar.filled}</Text>
            <Text dimColor>{taskBar.empty}</Text>
            <Text bold>{` ${tasks.done}/${tasks.total}`}</Text>
            <Text dimColor wrap="truncate-end">
              {isAllDone ? ' · all done' : tasks.current === undefined ? '' : ` · ${tasks.current}`}
            </Text>
          </Box>
        )}
      </Box>
    )
  })
}
