import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { SelectField, TextField } from './Field'

describe('TextField', () => {
  it('레이블과 입력을 연결한다', () => {
    render(<TextField label="이메일" />)

    expect(screen.getByLabelText('이메일')).toBeInTheDocument()
  })

  it('오류가 있으면 aria-invalid와 오류 문구를 입력에 연결한다', () => {
    render(<TextField label="이메일" error="이메일 형식이 아닙니다." />)

    const input = screen.getByLabelText('이메일')

    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAccessibleDescription('이메일 형식이 아닙니다.')
  })

  it('오류가 없으면 aria-invalid를 붙이지 않는다', () => {
    render(<TextField label="이메일" help="회사 메일도 사용할 수 있습니다." />)

    const input = screen.getByLabelText('이메일')

    expect(input).not.toHaveAttribute('aria-invalid')
    expect(input).toHaveAccessibleDescription('회사 메일도 사용할 수 있습니다.')
  })

  it('필수 입력임을 보조기술이 읽을 수 있게 알린다', () => {
    render(<TextField label="비밀번호" required />)

    // 필수 마커를 레이블 텍스트에 넣지 않는다. required 속성이 그 역할을 한다.
    expect(screen.getByLabelText('비밀번호')).toBeRequired()
  })

  it('레이블이 서로 접두사를 공유해도 정확히 구분된다', () => {
    render(
      <>
        <TextField label="비밀번호" required />
        <TextField label="비밀번호 확인" required />
      </>,
    )

    expect(screen.getByLabelText('비밀번호')).toHaveAccessibleName('비밀번호')
    expect(screen.getByLabelText('비밀번호 확인')).toHaveAccessibleName(
      '비밀번호 확인',
    )
  })
})

describe('SelectField', () => {
  it('레이블과 선택 요소를 연결한다', () => {
    render(
      <SelectField label="지역">
        <option value="">전체</option>
        <option value="SEOUL">서울</option>
      </SelectField>,
    )

    expect(screen.getByLabelText('지역')).toBeInTheDocument()
  })
})
