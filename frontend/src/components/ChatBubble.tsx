export default function ChatBubble({
  sender,
  content,
  streaming = false,
  time,
}: {
  sender: 'user' | 'companion' | 'system'
  content: string
  streaming?: boolean
  time?: string
}) {
  const isUser = sender === 'user'
  const isSystem = sender === 'system'
  if (isSystem) {
    return (
      <div className="flex justify-center py-1">
        <span className="rounded-full bg-cocoa-900 px-3 py-1 text-xs text-cocoa-500">{content}</span>
      </div>
    )
  }
  return (
    <div className={`flex animate-fadeUp flex-col ${isUser ? 'items-end' : 'items-start'}`}>
      <div
        className={`max-w-[78%] whitespace-pre-wrap rounded-2xl px-4 py-2.5 text-sm leading-relaxed ${
          isUser
            ? 'rounded-br-md bg-ember text-cocoa-950'
            : 'rounded-bl-md border border-cocoa-700 bg-cocoa-800 text-cocoa-100'
        }`}
      >
        {content}
        {streaming && <span className="marker-dot ml-1 animate-pulseSoft bg-ember-soft" />}
      </div>
      {time && <span className={`mt-1 px-1 text-[10px] text-cocoa-500`}>{time}</span>}
    </div>
  )
}
