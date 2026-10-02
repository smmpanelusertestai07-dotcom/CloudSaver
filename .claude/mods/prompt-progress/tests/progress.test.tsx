import { describe, expect, test } from 'claude-code/testing'
import type { On, RenderSurface } from 'claude-code'

const BAND = {
  component: 'AbovePrompt',
  props: {
    hasSurvey: false,
    isWorking: false,
    maxRows: 10,
    bodyColumns: 80,
    scroll: { offset: 0, bodyRows: 10 },
    view: {},
  },
} as const

const TYPED = { origin: { kind: 'composer' }, presentation: { isFullscreen: false, columns: 80 } } as const

/** The engine beneath the plugin: a quarter-full window, the task tools, and what draws. */
function standIn(on: On, surfaces: RenderSurface[], statuses: (string | undefined)[]): void {
  on('session.usage', () => ({
    value: { startedAt: 0, context: { tokens: 50_000, window: 200_000, percent: 25 }, rateLimits: [] },
  }))
  on('session.surfaces', () => ({ value: surfaces }))
  on('ui.status', ($, e) => {
    statuses.push(e.text)

    return { value: undefined }
  })
  on('command.register', ($, e) => ({ value: { command: e.name } }))
  on('tool.call', { tool: 'TaskCreate' }, ($, e) => ({
    result: { task: { id: e.subject === 'Ship it' ? '2' : '1', subject: e.subject } },
  }))
  on('tool.call', { tool: 'TaskUpdate' }, ($, e) => ({
    result: { success: true, taskId: e.taskId, updatedFields: ['status'] },
  }))
  on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
    const { Text } = $.ui.resolve(e)

    return <Text>engine band</Text>
  })
}

describe('prompt-progress', () => {
  test('draws the context and task bars above the prompt on the terminal and the desktop', async ($, on) => {
    const statuses: (string | undefined)[] = []
    standIn(on, ['desktop'], statuses)

    await $.tool.call({
      tool: 'TaskCreate',
      subject: 'Write tests',
      description: 'Cover the bars',
      activeForm: 'Writing tests',
    })
    await $.tool.call({ tool: 'TaskCreate', subject: 'Ship it', description: 'Push the branch' })
    await $.tool.call({ tool: 'TaskUpdate', taskId: '1', status: 'in_progress' })

    for (const surface of ['terminal', 'desktop'] as const) {
      const ui = await $.ui.mount({ plugin: 'prompt-progress', surface, ...BAND })
      expect(await ui.find({ type: 'Text', text: ' 25%' })).toBeDefined()
      expect(await ui.find({ type: 'Text', text: '50k/200k tokens' })).toBeDefined()
      expect(await ui.find({ type: 'Text', text: ' 0/2' })).toBeDefined()
      expect(await ui.find({ type: 'Text', text: 'Writing tests' })).toBeDefined()
      expect(await ui.find({ type: 'Text', text: 'engine band' })).toBeUndefined()
      await ui.unmount()
    }
    // A desktop draws the band, so nothing was pinned as a status line.
    expect(statuses.filter(text => text !== undefined)).toEqual([])
  })

  test('counts finished tasks, names the next one, then says all done', async ($, on) => {
    standIn(on, ['terminal'], [])
    await $.tool.call({ tool: 'TaskCreate', subject: 'Write tests', description: 'Cover the bars' })
    await $.tool.call({ tool: 'TaskCreate', subject: 'Ship it', description: 'Push the branch' })
    await $.tool.call({ tool: 'TaskUpdate', taskId: '1', status: 'completed' })

    const half = await $.ui.mount({ plugin: 'prompt-progress', surface: 'terminal', ...BAND })
    expect(await half.find({ type: 'Text', text: ' 1/2' })).toBeDefined()
    expect(await half.find({ type: 'Text', text: 'next: Ship it' })).toBeDefined()
    await half.unmount()

    await $.tool.call({ tool: 'TaskUpdate', taskId: '2', status: 'completed' })
    const done = await $.ui.mount({ plugin: 'prompt-progress', surface: 'terminal', ...BAND })
    expect(await done.find({ type: 'Text', text: ' 2/2' })).toBeDefined()
    expect(await done.find({ type: 'Text', text: 'all done' })).toBeDefined()
    await done.unmount()
  })

  test('pins the bars as the status line where no attached surface draws the band', async ($, on) => {
    const statuses: (string | undefined)[] = []
    standIn(on, [], statuses)

    await $.tool.call({ tool: 'TaskCreate', subject: 'Write tests', description: 'Cover the bars' })

    const line = statuses.at(-1)
    expect(line).toMatch(/^Context █{3}░{9} 25% · 50k\/200k {3}Tasks {3}░{12} 0\/1 · next: Write tests$/)
  })

  test('follows a TodoWrite list', async ($, on) => {
    standIn(on, ['terminal'], [])
    on('tool.call', { tool: 'TodoWrite' }, ($, e) => ({ result: { oldTodos: [], newTodos: e.todos } }))

    await $.tool.call({
      tool: 'TodoWrite',
      todos: [
        { content: 'Read the code', status: 'completed', activeForm: 'Reading the code' },
        { content: 'Write the fix', status: 'in_progress', activeForm: 'Writing the fix' },
        { content: 'Run the tests', status: 'pending', activeForm: 'Running the tests' },
      ],
    })

    const ui = await $.ui.mount({ plugin: 'prompt-progress', surface: 'desktop', ...BAND })
    expect(await ui.find({ type: 'Text', text: ' 1/3' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'Writing the fix' })).toBeDefined()
    await ui.unmount()
  })

  test('a failed reading never fails the tool call it follows', async ($, on) => {
    on('session.usage', () => ({ deny: 'no usage in this test' }))
    on('session.surfaces', () => ({ value: [] }))
    on('ui.status', () => ({ value: undefined }))
    on('tool.call', { tool: 'TaskCreate' }, ($, e) => ({ result: { task: { id: '7', subject: e.subject } } }))
    on('ui.render', { component: 'AbovePrompt' }, ($, e) => {
      const { Text } = $.ui.resolve(e)

      return <Text>engine band</Text>
    })

    const ran = await $.tool.call({ tool: 'TaskCreate', subject: 'Keep going', description: 'Despite it' })
    expect(ran.result).toEqual({ task: { id: '7', subject: 'Keep going' } })

    const ui = await $.ui.mount({ plugin: 'prompt-progress', surface: 'terminal', ...BAND })
    expect(await ui.find({ type: 'Text', text: ' 0/1' })).toBeDefined()
    expect(await ui.find({ type: 'Text', text: 'Context' })).toBeUndefined()
    await ui.unmount()
  })

  test('/progress off hides the band and /progress on brings it back', async ($, on) => {
    standIn(on, ['terminal'], [])
    await $.tool.call({ tool: 'TaskCreate', subject: 'Write tests', description: 'Cover the bars' })

    const off = await $.command.run({ command: 'progress', args: 'off', ...TYPED })
    expect(off.text).toMatch(/off/)
    const hidden = await $.ui.mount({ plugin: 'prompt-progress', surface: 'terminal', ...BAND })
    expect(await hidden.find({ type: 'Text', text: 'engine band' })).toBeDefined()
    expect(await hidden.find({ type: 'Text', text: 'Context' })).toBeUndefined()
    await hidden.unmount()

    const shown = await $.command.run({ command: 'progress', args: 'on', ...TYPED })
    expect(shown.text).toMatch(/25% · 50k\/200k/)
    expect(shown.text).toMatch(/Drawn above the prompt on: terminal/)
    const back = await $.ui.mount({ plugin: 'prompt-progress', surface: 'terminal', ...BAND })
    expect(await back.find({ type: 'Text', text: 'Context' })).toBeDefined()
    await back.unmount()
  })
})
