import { Button } from '../../../../shared/ui/Button'
import { TextField } from '../../../../shared/ui/Field'
import {
  MAX_RANGES_PER_DAY,
  emptyRange,
  rangeErrorKey,
  removeAt,
  replaceAt,
} from '../model/weeklySchedule'
import { DAY_LABEL, type DayOfWeek, type TimeRange } from '../model/types'

interface Props {
  dayOfWeek: DayOfWeek
  section: 'business' | 'break' | 'slot'
  legend: string
  ranges: readonly TimeRange[]
  /** 제출을 막는 오류. 서버 판정과 같은 규칙으로 미리 보여 준다. */
  errors: Readonly<Record<string, string>>
  /** 제출을 막지 않는 경고. 게시된 값 기준의 참고 정보다. */
  warnings?: Readonly<Record<string, string>>
  addLabel: string
  onChange: (ranges: TimeRange[]) => void
}

/**
 * 하루의 시각 구간 목록 편집기.
 *
 * 구간은 `[startTime, endTime)` 반열린 구간이다. 그래서 앞 구간의 종료와 뒤
 * 구간의 시작이 같은 것은 겹침이 아니다.
 *
 * 한 화면에 시각 입력이 수십 개다. 입력 레이블을 "시작"처럼 짧게 두는 대신 각
 * 구간을 이름 있는 group으로 감싼다. 보조기술은 group 이름을 먼저 읽으므로
 * 어느 요일의 몇 번째 구간인지 알 수 있고, 화면에는 짧은 레이블만 남는다.
 */
export function RangeListEditor({
  dayOfWeek,
  section,
  legend,
  ranges,
  errors,
  warnings,
  addLabel,
  onChange,
}: Props) {
  return (
    <div className="op-day__group">
      <p className="op-day__group-title">{legend}</p>

      {ranges.map((range, index) => {
        const key = rangeErrorKey(dayOfWeek, section, index)
        const error = errors[key]
        const warning = warnings?.[key]
        const position = `${DAY_LABEL[dayOfWeek]} ${legend} ${index + 1}번`

        return (
          <div className="op-range" key={key} role="group" aria-label={position}>
            <div className="op-range__field">
              <TextField
                label="시작"
                type="time"
                value={range.startTime}
                error={null}
                onChange={(event) =>
                  onChange(
                    replaceAt(ranges, index, {
                      ...range,
                      startTime: event.target.value,
                    }),
                  )
                }
              />
            </div>
            <div className="op-range__field">
              <TextField
                label="종료"
                type="time"
                value={range.endTime}
                error={null}
                onChange={(event) =>
                  onChange(
                    replaceAt(ranges, index, {
                      ...range,
                      endTime: event.target.value,
                    }),
                  )
                }
              />
            </div>
            <Button
              variant="ghost"
              size="sm"
              aria-label={`${position} 삭제`}
              onClick={() => onChange(removeAt(ranges, index))}
            >
              삭제
            </Button>
            {error !== undefined && (
              <p className="op-range__error" role="alert">
                {error}
              </p>
            )}
            {error === undefined && warning !== undefined && (
              <p className="op-range__warning">{warning}</p>
            )}
          </div>
        )
      })}

      <div className="op-actions">
        <Button
          variant="ghost"
          size="sm"
          disabled={ranges.length >= MAX_RANGES_PER_DAY}
          onClick={() => onChange([...ranges, emptyRange()])}
        >
          {addLabel}
        </Button>
      </div>
    </div>
  )
}
