export const ACCESS_SOURCE_META: Record<
  string,
  { label: string; variant: "default" | "secondary" | "outline" }
> = {
  TEAM: {
    label: "Team grant",
    variant: "secondary",
  },
  USER_EXTENSION: {
    label: "User extension",
    variant: "outline",
  },
  TEAM_AND_USER_EXTENSION: {
    label: "Team grant + extension",
    variant: "default",
  },
}
