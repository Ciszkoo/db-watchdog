import type { HTMLAttributes } from "react"

import { cn } from "~/lib/utils"

type AlertProps = HTMLAttributes<HTMLDivElement> & {
  variant?: "default" | "destructive"
}

export function Alert({
  className,
  variant = "default",
  ...props
}: AlertProps) {
  return (
    <div
      role="alert"
      className={cn(
        "rounded-[1.25rem] border px-4 py-4 text-sm",
        variant === "destructive"
          ? "border-red-200 bg-red-50 text-red-950"
          : "border-amber-200 bg-amber-50 text-amber-950",
        className
      )}
      {...props}
    />
  )
}

export function AlertTitle({
  className,
  ...props
}: HTMLAttributes<HTMLParagraphElement>) {
  return <p className={cn("font-semibold", className)} {...props} />
}

export function AlertDescription({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("mt-1 leading-6", className)} {...props} />
}
