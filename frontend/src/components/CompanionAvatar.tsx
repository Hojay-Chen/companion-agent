export default function CompanionAvatar({
  name,
  gender,
  size = 48,
}: {
  name: string
  gender?: string | null
  size?: number
}) {
  const initial = name ? name.charAt(0) : '伴'
  const gradient =
    gender === 'male'
      ? 'from-sky-800 via-indigo-800 to-cocoa-800'
      : 'from-ember-deep via-rosewood to-cocoa-800'
  return (
    <div
      className={`flex shrink-0 items-center justify-center rounded-full bg-gradient-to-br ${gradient} text-cocoa-50 font-semibold shadow-panel select-none`}
      style={{ width: size, height: size, fontSize: Math.round(size * 0.42) }}
    >
      {initial}
    </div>
  )
}
