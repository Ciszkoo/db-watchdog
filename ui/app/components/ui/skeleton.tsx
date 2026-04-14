import type { HTMLAttributes } from "react"

import { cn } from "~/lib/utils"

export function Skeleton({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "animate-pulse rounded-2xl bg-gradient-to-r from-stone-200 via-stone-100 to-stone-200",
        className
      )}
      {...props}
    />
  )
}
