import type { HTMLAttributes } from "react"

import { cn } from "~/lib/utils"

export function Card({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn(
        "rounded-[1.75rem] border border-stone-200/80 bg-white/90 shadow-[0_20px_60px_-32px_rgba(41,37,36,0.45)] backdrop-blur",
        className
      )}
      {...props}
    />
  )
}

export function CardHeader({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("flex flex-col gap-3 p-6", className)} {...props} />
}

export function CardTitle({
  className,
  ...props
}: HTMLAttributes<HTMLHeadingElement>) {
  return (
    <h2
      className={cn(
        "text-lg font-semibold tracking-tight text-stone-950",
        className
      )}
      {...props}
    />
  )
}

export function CardDescription({
  className,
  ...props
}: HTMLAttributes<HTMLParagraphElement>) {
  return (
    <p className={cn("text-sm leading-6 text-stone-600", className)} {...props} />
  )
}

export function CardContent({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("px-6 pb-6", className)} {...props} />
}

export function CardFooter({
  className,
  ...props
}: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("flex items-center gap-3 px-6 pb-6 pt-1", className)}
      {...props}
    />
  )
}
