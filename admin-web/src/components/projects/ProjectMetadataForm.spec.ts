import { mount } from '@vue/test-utils'
import { defineComponent, nextTick, type PropType } from 'vue'
import { describe, expect, it, vi } from 'vitest'

import type { MediaPickerLoad } from '@/components/media/MediaPickerDialog.vue'
import { createBlock } from '@/types/blocks'
import type {
  MediaAssetSummaryDto,
  MediaKind,
  ProjectMediaDto,
  ProjectWorkspaceDto,
  TaxonomyWorkspaceDto,
} from '@/types/content'

import ProjectMetadataForm from './ProjectMetadataForm.vue'

const IDS = Object.freeze({
  project: '10000000-0000-4000-8000-000000000001',
  tagA: '20000000-0000-4000-8000-000000000001',
  tagB: '20000000-0000-4000-8000-000000000002',
  skillA: '30000000-0000-4000-8000-000000000001',
  skillB: '30000000-0000-4000-8000-000000000002',
  assetA: '40000000-0000-4000-8000-000000000001',
  assetB: '40000000-0000-4000-8000-000000000002',
  assetC: '40000000-0000-4000-8000-000000000003',
  assetD: '40000000-0000-4000-8000-000000000004',
})

const tags: TaxonomyWorkspaceDto[] = [
  {
    id: IDS.tagA,
    normalizedKey: 'gameplay',
    version: 7,
    names: { 'zh-CN': '玩法', en: 'Gameplay' },
  },
  {
    id: IDS.tagB,
    normalizedKey: 'systems',
    version: 8,
    names: { 'zh-CN': '系统', en: 'Systems' },
  },
]

const skills: TaxonomyWorkspaceDto[] = [
  {
    id: IDS.skillA,
    normalizedKey: 'unreal-engine',
    version: 4,
    names: { 'zh-CN': '虚幻引擎', en: 'Unreal Engine' },
  },
  {
    id: IDS.skillB,
    normalizedKey: 'cpp',
    version: 5,
    names: { 'zh-CN': 'C++', en: 'C++' },
  },
]

function projectMedia(
  assetId: string,
  usage: ProjectMediaDto['usage'],
  sortOrder: number,
): ProjectMediaDto {
  return {
    assetId,
    usage,
    sortOrder,
    layout: 'wide',
    objectPosition: '40% 60%',
    credit: 'Yi Jiaxuan',
    sourceUrl: 'https://example.test/source',
  }
}

function workspace(overrides: Partial<ProjectWorkspaceDto> = {}): ProjectWorkspaceDto {
  return {
    id: IDS.project,
    externalKey: 'project-echoes',
    slug: 'project-echoes',
    number: '01',
    sortOrder: 3,
    featured: true,
    visible: true,
    publicationDirty: true,
    version: 9,
    translations: {
      'zh-CN': {
        status: '开发中',
        eyebrow: '游戏开发',
        title: '回声计划',
        summary: '一个双语项目。',
        seoTitle: '回声计划 | 易嘉轩',
        seoDescription: '项目搜索摘要。',
      },
      en: {
        status: 'In development',
        eyebrow: 'Game development',
        title: 'Project Echoes',
        summary: 'A bilingual project.',
        seoTitle: 'Project Echoes | Yi Jiaxuan',
        seoDescription: 'Project search summary.',
      },
    },
    tags: [
      {
        id: IDS.tagA,
        normalizedKey: tags[0]!.normalizedKey,
        sortOrder: 0,
        names: { ...tags[0]!.names },
      },
    ],
    skills: [
      {
        id: IDS.skillA,
        normalizedKey: skills[0]!.normalizedKey,
        sortOrder: 0,
        names: { ...skills[0]!.names },
      },
    ],
    media: [projectMedia(IDS.assetA, 'COVER', 0)],
    blocks: [createBlock('MARKDOWN')],
    ...overrides,
  }
}

const readyImage: MediaAssetSummaryDto = {
  id: IDS.assetB,
  kind: 'IMAGE',
  originalFilename: 'project-shot.png',
  mimeType: 'image/png',
  status: 'READY',
  previewUrl: `/api/admin/media/${IDS.assetB}/preview/w1280`,
  width: 1920,
  height: 1080,
}

const MediaPickerStub = defineComponent({
  name: 'MediaPickerDialog',
  props: {
    open: { type: Boolean, required: true },
    accept: {
      type: Array as PropType<readonly MediaKind[]>,
      required: true,
    },
    load: {
      type: Function as PropType<MediaPickerLoad>,
      required: true,
    },
  },
  emits: ['select', 'close', 'update:open'],
  template: '<div v-if="open" data-picker-stub />',
})

function mountForm(
  modelValue = workspace(),
  options: {
    locale?: 'zh-CN' | 'en'
    disabled?: boolean
    fieldErrors?: Readonly<Record<string, string>>
    loadMediaPage?: MediaPickerLoad
  } = {},
) {
  return mount(ProjectMetadataForm, {
    props: {
      modelValue,
      locale: options.locale ?? 'en',
      tagCatalog: tags,
      skillCatalog: skills,
      disabled: options.disabled,
      fieldErrors: options.fieldErrors,
      loadMediaPage: options.loadMediaPage,
    },
    global: {
      stubs: { MediaPickerDialog: MediaPickerStub },
    },
  })
}

function latestModel(wrapper: ReturnType<typeof mountForm>): ProjectWorkspaceDto {
  const event = wrapper.emitted('update:modelValue')?.at(-1)
  if (event === undefined) throw new Error('missing update:modelValue event')
  return event[0] as ProjectWorkspaceDto
}

describe('ProjectMetadataForm root and translations', () => {
  it('binds every root metadata field and keeps the stable external key read-only', async () => {
    const original = workspace()
    const wrapper = mountForm(original)
    const externalKey = wrapper.get<HTMLInputElement>('[data-field="externalKey"]')

    expect(externalKey.element.value).toBe('project-echoes')
    expect(externalKey.attributes('readonly')).toBeDefined()
    expect(wrapper.get<HTMLInputElement>('[data-field="slug"]').element.value).toBe(
      'project-echoes',
    )
    expect(wrapper.get<HTMLInputElement>('[data-field="number"]').element.value).toBe('01')
    expect(wrapper.get<HTMLInputElement>('[data-field="sortOrder"]').element.value).toBe('3')

    await wrapper.get<HTMLInputElement>('[data-field="slug"]').setValue('project-rift')
    const slugUpdate = latestModel(wrapper)
    expect(slugUpdate).not.toBe(original)
    expect(slugUpdate.slug).toBe('project-rift')
    expect(slugUpdate.blocks).toBe(original.blocks)
    expect(original.slug).toBe('project-echoes')

    await wrapper.setProps({ modelValue: slugUpdate })
    await wrapper.get<HTMLInputElement>('[data-field="number"]').setValue('02')
    const numberUpdate = latestModel(wrapper)
    expect(numberUpdate.number).toBe('02')
    expect(numberUpdate.blocks).toBe(original.blocks)

    await wrapper.setProps({ modelValue: numberUpdate })
    await wrapper.get<HTMLInputElement>('[data-field="sortOrder"]').setValue('6')
    const orderUpdate = latestModel(wrapper)
    expect(orderUpdate.sortOrder).toBe(6)

    await wrapper.setProps({ modelValue: orderUpdate })
    await wrapper.get<HTMLInputElement>('[data-field="featured"]').setValue(false)
    expect(latestModel(wrapper).featured).toBe(false)

    await wrapper.setProps({ modelValue: latestModel(wrapper) })
    await wrapper.get<HTMLInputElement>('[data-field="visible"]').setValue(false)
    expect(latestModel(wrapper).visible).toBe(false)
  })

  it('renders exactly six current-locale copy controls and updates only that locale', async () => {
    const original = workspace()
    const wrapper = mountForm(original, { locale: 'en' })
    const expectedFields = [
      'status',
      'eyebrow',
      'title',
      'summary',
      'seoTitle',
      'seoDescription',
    ].map((key) => `translations.en.${key}`)

    expect(
      wrapper
        .findAll('[data-field^="translations.en."]')
        .map((control) => control.attributes('data-field')),
    ).toEqual(expectedFields)
    expect(wrapper.find('[data-field^="translations.zh-CN."]').exists()).toBe(false)

    const title = wrapper.get<HTMLInputElement>('[data-field="translations.en.title"]')
    expect(title.attributes('lang')).toBe('en')
    await title.setValue('Rift Prototype')

    const updated = latestModel(wrapper)
    expect(updated.translations.en.title).toBe('Rift Prototype')
    expect(updated.translations.en).not.toBe(original.translations.en)
    expect(updated.translations['zh-CN']).toBe(original.translations['zh-CN'])
    expect(updated.blocks).toBe(original.blocks)
    expect(original.translations.en.title).toBe('Project Echoes')
  })

  it('rolls an invalid integer edit back to the controlled sort order', async () => {
    const wrapper = mountForm(workspace())
    const sortOrder = wrapper.get<HTMLInputElement>('[data-field="sortOrder"]')

    await sortOrder.setValue('1.5')

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(sortOrder.element.value).toBe('3')
  })

  it('disables the complete editor without changing read-only semantics', async () => {
    const wrapper = mountForm(workspace(), { disabled: true })

    expect(wrapper.get('fieldset[data-section="project-metadata"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-field="externalKey"]').attributes('readonly')).toBeDefined()
    expect(wrapper.get('[data-action="add-project-media"]').attributes('disabled')).toBeDefined()

    await wrapper.get('[data-action="add-project-media"]').trigger('click')
    expect(wrapper.findComponent(MediaPickerStub).props('open')).toBe(false)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})

describe('ProjectMetadataForm taxonomy selection', () => {
  it('creates embedded refs instead of leaking global workspace DTOs', async () => {
    const original = workspace()
    const wrapper = mountForm(original)
    const secondTag = wrapper.get<HTMLInputElement>(
      `[data-taxonomy="tags"][data-taxonomy-id="${IDS.tagB}"]`,
    )
    const secondSkill = wrapper.get<HTMLInputElement>(
      `[data-taxonomy="skills"][data-taxonomy-id="${IDS.skillB}"]`,
    )

    expect(secondTag.element.checked).toBe(false)
    await secondTag.setValue(true)
    const tagUpdate = latestModel(wrapper)
    const addedTag = tagUpdate.tags[1]
    expect(addedTag).toEqual({
      id: IDS.tagB,
      normalizedKey: 'systems',
      sortOrder: 1,
      names: { 'zh-CN': '系统', en: 'Systems' },
    })
    expect(Object.keys(addedTag!).sort()).toEqual([
      'id',
      'names',
      'normalizedKey',
      'sortOrder',
    ])
    expect(addedTag?.names).not.toBe(tags[1]!.names)
    expect(tagUpdate.blocks).toBe(original.blocks)

    await wrapper.setProps({ modelValue: tagUpdate })
    await secondSkill.setValue(true)
    const skillUpdate = latestModel(wrapper)
    expect(skillUpdate.skills.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: IDS.skillA, sortOrder: 0 },
      { id: IDS.skillB, sortOrder: 1 },
    ])
    expect(Object.hasOwn(skillUpdate.skills[1]!, 'version')).toBe(false)
  })

  it('renumbers refs after removal and appends a reselected item deterministically', async () => {
    const wrapper = mountForm(
      workspace({
        tags: [
          {
            id: IDS.tagA,
            normalizedKey: tags[0]!.normalizedKey,
            sortOrder: 9,
            names: { ...tags[0]!.names },
          },
          {
            id: IDS.tagB,
            normalizedKey: tags[1]!.normalizedKey,
            sortOrder: 4,
            names: { ...tags[1]!.names },
          },
        ],
      }),
    )
    const firstTag = wrapper.get<HTMLInputElement>(
      `[data-taxonomy="tags"][data-taxonomy-id="${IDS.tagA}"]`,
    )

    await firstTag.setValue(false)
    const removed = latestModel(wrapper)
    expect(removed.tags.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: IDS.tagB, sortOrder: 0 },
    ])

    await wrapper.setProps({ modelValue: removed })
    await firstTag.setValue(true)
    expect(latestModel(wrapper).tags.map(({ id, sortOrder }) => ({ id, sortOrder }))).toEqual([
      { id: IDS.tagB, sortOrder: 0 },
      { id: IDS.tagA, sortOrder: 1 },
    ])
  })
})

describe('ProjectMetadataForm media', () => {
  it('forwards the injected loader and accepts only trusted READY images', async () => {
    const loadMediaPage = vi.fn<MediaPickerLoad>().mockResolvedValue({
      items: [],
      page: 0,
      size: 24,
      totalItems: 0,
      totalPages: 0,
    })
    const original = workspace()
    const wrapper = mountForm(original, { loadMediaPage })

    await wrapper.get('[data-action="add-project-media"]').trigger('click')
    const picker = wrapper.findComponent(MediaPickerStub)
    expect(picker.props('open')).toBe(true)
    expect(picker.props('accept')).toEqual(['IMAGE'])
    await (picker.props('load') as MediaPickerLoad)({ page: 0, size: 24 })
    expect(loadMediaPage).toHaveBeenCalledWith({ page: 0, size: 24 })

    for (const forged of [
      { ...readyImage, status: 'PROCESSING' },
      { ...readyImage, kind: 'PDF', mimeType: 'application/pdf' },
      { ...readyImage, mimeType: 'application/pdf' },
      { ...readyImage, id: '..' },
    ]) {
      picker.vm.$emit('select', forged)
      await nextTick()
    }
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    picker.vm.$emit('select', readyImage)
    await nextTick()
    const updated = latestModel(wrapper)
    expect(updated.media.at(-1)).toEqual({
      assetId: IDS.assetB,
      usage: 'DETAIL',
      sortOrder: 0,
      layout: 'standard',
      objectPosition: '50% 50%',
      credit: '',
      sourceUrl: '',
    })
    expect(updated.blocks).toBe(original.blocks)
    expect(original.media).toHaveLength(1)
    expect(picker.props('open')).toBe(false)
  })

  it('prevents duplicate asset/usage pairs from forged picker events', async () => {
    const wrapper = mountForm(
      workspace({ media: [projectMedia(IDS.assetB, 'DETAIL', 0)] }),
    )
    await wrapper.get('[data-action="add-project-media"]').trigger('click')
    wrapper.findComponent(MediaPickerStub).vm.$emit('select', readyImage)
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('updates every media field immutably and reports non-HTTPS source URLs', async () => {
    const original = workspace()
    const wrapper = mountForm(original)

    await wrapper.get<HTMLSelectElement>('[data-field="media.0.layout"]').setValue('standard')
    const layoutUpdate = latestModel(wrapper)
    expect(layoutUpdate.media[0]?.layout).toBe('standard')
    expect(layoutUpdate.media[0]).not.toBe(original.media[0])
    expect(original.media[0]?.layout).toBe('wide')
    expect(layoutUpdate.blocks).toBe(original.blocks)

    await wrapper.setProps({ modelValue: layoutUpdate })
    await wrapper
      .get<HTMLInputElement>('[data-field="media.0.objectPosition"]')
      .setValue('50% 20%')
    expect(latestModel(wrapper).media[0]?.objectPosition).toBe('50% 20%')

    await wrapper.setProps({ modelValue: latestModel(wrapper) })
    await wrapper.get<HTMLInputElement>('[data-field="media.0.credit"]').setValue('Studio')
    expect(latestModel(wrapper).media[0]?.credit).toBe('Studio')

    await wrapper.setProps({ modelValue: latestModel(wrapper) })
    const source = wrapper.get<HTMLInputElement>('[data-field="media.0.sourceUrl"]')
    await source.setValue('http://example.test/unsafe')
    const sourceUpdate = latestModel(wrapper)
    await wrapper.setProps({ modelValue: sourceUpdate })

    const updatedSource = wrapper.get('[data-field="media.0.sourceUrl"]')
    expect(updatedSource.attributes('type')).toBe('url')
    expect(updatedSource.attributes('aria-invalid')).toBe('true')
    expect(wrapper.get(`#${updatedSource.attributes('aria-describedby')}`).text()).toContain(
      'HTTPS',
    )
  })

  it('blocks duplicate pairs on usage changes and normalizes both usage groups', async () => {
    const duplicate = workspace({
      media: [
        projectMedia(IDS.assetA, 'COVER', 0),
        projectMedia(IDS.assetA, 'DETAIL', 0),
      ],
    })
    const duplicateWrapper = mountForm(duplicate)
    const duplicateUsage = duplicateWrapper.get<HTMLSelectElement>(
      '[data-field="media.1.usage"]',
    )
    await duplicateUsage.setValue('COVER')
    expect(duplicateWrapper.emitted('update:modelValue')).toBeUndefined()
    expect(duplicateUsage.element.value).toBe('DETAIL')

    const wrapper = mountForm(
      workspace({
        media: [
          projectMedia(IDS.assetA, 'COVER', 8),
          projectMedia(IDS.assetB, 'CARD', 5),
          projectMedia(IDS.assetC, 'COVER', 2),
        ],
      }),
    )
    await wrapper.get<HTMLSelectElement>('[data-field="media.2.usage"]').setValue('CARD')
    const updated = latestModel(wrapper)
    expect(
      updated.media.map(({ assetId, usage, sortOrder }) => ({ assetId, usage, sortOrder })),
    ).toEqual([
      { assetId: IDS.assetA, usage: 'COVER', sortOrder: 0 },
      { assetId: IDS.assetB, usage: 'CARD', sortOrder: 0 },
      { assetId: IDS.assetC, usage: 'CARD', sortOrder: 1 },
    ])
  })

  it('moves within each usage, deletes safely, and keeps per-usage orders contiguous', async () => {
    const original = workspace({
      media: [
        projectMedia(IDS.assetA, 'COVER', 9),
        projectMedia(IDS.assetB, 'DETAIL', 7),
        projectMedia(IDS.assetC, 'COVER', 3),
        projectMedia(IDS.assetD, 'DETAIL', 4),
      ],
    })
    const wrapper = mountForm(original)

    await wrapper
      .get('[data-project-media-index="0"] [data-action="move-media-down"]')
      .trigger('click')
    const moved = latestModel(wrapper)
    expect(moved.media.map(({ assetId }) => assetId)).toEqual([
      IDS.assetC,
      IDS.assetB,
      IDS.assetA,
      IDS.assetD,
    ])
    expect(
      moved.media
        .filter(({ usage }) => usage === 'COVER')
        .map(({ assetId, sortOrder }) => ({ assetId, sortOrder })),
    ).toEqual([
      { assetId: IDS.assetC, sortOrder: 0 },
      { assetId: IDS.assetA, sortOrder: 1 },
    ])
    expect(
      moved.media
        .filter(({ usage }) => usage === 'DETAIL')
        .map(({ sortOrder }) => sortOrder),
    ).toEqual([0, 1])

    await wrapper.setProps({ modelValue: moved })
    await wrapper
      .get('[data-project-media-index="1"] [data-action="delete-media"]')
      .trigger('click')
    const deleted = latestModel(wrapper)
    expect(deleted.media.map(({ assetId }) => assetId)).toEqual([
      IDS.assetC,
      IDS.assetA,
      IDS.assetD,
    ])
    expect(
      deleted.media
        .filter(({ usage }) => usage === 'DETAIL')
        .map(({ sortOrder }) => sortOrder),
    ).toEqual([0])
    expect(deleted.blocks).toBe(original.blocks)
  })
})

describe('ProjectMetadataForm field errors', () => {
  it('associates root, translated, and indexed media errors as escaped text', () => {
    const unsafeMessage = '<img src=x onerror=alert(1)>'
    const wrapper = mountForm(workspace(), {
      locale: 'en',
      fieldErrors: {
        externalKey: '稳定键不可修改',
        slug: '请输入有效 slug',
        featured: '请确认精选状态',
        'translations.en.title': '请输入英文标题',
        'media[0].sourceUrl': unsafeMessage,
      },
    })

    for (const selector of [
      '[data-field="externalKey"]',
      '[data-field="slug"]',
      '[data-field="featured"]',
      '[data-field="translations.en.title"]',
      '[data-field="media.0.sourceUrl"]',
    ]) {
      const control = wrapper.get(selector)
      const description = control.attributes('aria-describedby')
      expect(control.attributes('aria-invalid')).toBe('true')
      expect(description).toBeTruthy()
      expect(wrapper.get(`#${description}`).attributes('role')).toBe('alert')
    }

    expect(wrapper.text()).toContain(unsafeMessage)
    expect(wrapper.html()).toContain('&lt;img src=x onerror=alert(1)&gt;')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
