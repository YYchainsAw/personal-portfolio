import { describe, expect, it } from 'vitest'

import type { MediaAssetSummaryDto, MediaAssetView } from '@/types/content'

import {
  isReadyImageSelection,
  isReadyPdfSelection,
  isResolvedImage,
  isResolvedPdf,
  mediaTranslationState,
} from './blockMedia'

const imageId = '10000000-0000-4000-8000-000000000001'
const pdfId = '10000000-0000-4000-8000-000000000002'

function summary(
  id: string,
  kind: MediaAssetSummaryDto['kind'],
  mimeType: MediaAssetSummaryDto['mimeType'],
): MediaAssetSummaryDto {
  return {
    id,
    kind,
    mimeType,
    status: 'READY',
    originalFilename: kind === 'IMAGE' ? 'cover.jpg' : 'resume.pdf',
    previewUrl: null,
    width: kind === 'IMAGE' ? 1280 : null,
    height: kind === 'IMAGE' ? 720 : null,
  }
}

function detailed(
  id: string,
  mimeType: MediaAssetView['mimeType'],
): MediaAssetView {
  const image = mimeType !== 'application/pdf'
  return {
    id,
    originalFilename: image ? 'cover.jpg' : 'resume.pdf',
    mimeType,
    byteSize: 1024,
    width: image ? 1280 : null,
    height: image ? 720 : null,
    sha256: 'a'.repeat(64),
    status: 'READY',
    version: 1,
    createdAt: '2026-07-18T00:00:00Z',
    updatedAt: '2026-07-18T00:00:01Z',
    translations: [
      {
        locale: 'zh-CN',
        altText: '中文替代文本',
        caption: '',
        credit: '',
        sourceUrl: null,
      },
      {
        locale: 'en',
        altText: '   ',
        caption: 'English caption',
        credit: '',
        sourceUrl: null,
      },
    ],
    variants: [],
  }
}

describe('blockMedia', () => {
  it('strictly accepts only READY UUID image selections with matching JPEG/PNG MIME', () => {
    expect(isReadyImageSelection(summary(imageId, 'IMAGE', 'image/jpeg'))).toBe(true)
    expect(isReadyImageSelection(summary(imageId, 'IMAGE', 'image/png'))).toBe(true)

    expect(isReadyImageSelection({ ...summary(imageId, 'IMAGE', 'image/jpeg'), status: 'PROCESSING' })).toBe(false)
    expect(isReadyImageSelection({ ...summary(imageId, 'IMAGE', 'image/jpeg'), id: 'not-a-uuid' })).toBe(false)
    expect(isReadyImageSelection(summary(imageId, 'PDF', 'application/pdf'))).toBe(false)
    expect(isReadyImageSelection({ ...summary(imageId, 'IMAGE', 'image/jpeg'), mimeType: 'application/pdf' })).toBe(false)
    expect(isReadyImageSelection(null)).toBe(false)
  })

  it('strictly accepts only READY PDF selections and excludes FILE and images', () => {
    expect(isReadyPdfSelection(summary(pdfId, 'PDF', 'application/pdf'))).toBe(true)
    expect(isReadyPdfSelection(summary(pdfId, 'FILE', 'application/pdf'))).toBe(false)
    expect(isReadyPdfSelection(summary(pdfId, 'IMAGE', 'image/png'))).toBe(false)
    expect(isReadyPdfSelection({ ...summary(pdfId, 'PDF', 'application/pdf'), status: 'ARCHIVED' })).toBe(false)
  })

  it('rejects mismatched, non-ready, and wrong-MIME resolver results', () => {
    const image = detailed(imageId, 'image/jpeg')
    const pdf = detailed(pdfId, 'application/pdf')

    expect(isResolvedImage(image, imageId)).toBe(true)
    expect(isResolvedImage({ ...image, id: imageId.toUpperCase() }, imageId)).toBe(true)
    expect(isResolvedImage({ ...image, id: pdfId }, imageId)).toBe(false)
    expect(isResolvedImage({ ...image, status: 'ARCHIVED' }, imageId)).toBe(false)
    expect(isResolvedImage(pdf, pdfId)).toBe(false)

    expect(isResolvedPdf(pdf, pdfId)).toBe(true)
    expect(isResolvedPdf({ ...pdf, status: 'PROCESSING' }, pdfId)).toBe(false)
    expect(isResolvedPdf(image, imageId)).toBe(false)
  })

  it('reports alt text and caption independently for both locales without inventing copy', () => {
    const asset = detailed(imageId, 'image/png')

    expect(mediaTranslationState(asset, 'zh-CN')).toEqual({
      locale: 'zh-CN',
      altText: { value: '中文替代文本', complete: true },
      caption: { value: '', complete: false },
    })
    expect(mediaTranslationState(asset, 'en')).toEqual({
      locale: 'en',
      altText: { value: '   ', complete: false },
      caption: { value: 'English caption', complete: true },
    })
  })
})
