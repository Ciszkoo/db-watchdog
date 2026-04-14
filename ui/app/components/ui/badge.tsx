import { cva, type VariantProps } from "class-variance-authority"
import type { HTMLAttributes } from "react"

import { cn } from "~/lib/utils"

const badgeVariants = cva(
  "inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-[0.16em]",
  {
    variants: {
      variant: {
        default: "bg-stone-950 text-stone-50",
        secondary: "bg-teal-100 text-teal-900",
        outline: "border border-stone-300 bg-white/70 text-stone-700",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

type BadgeProps = HTMLAttributes<HTMLDivElement> &
  VariantProps<typeof badgeVariants>

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />
}
