export const TEST_DATABASE = {
  databaseName: "playwright_ui_proxy_e2e",
  engine: "postgres",
  host: "localhost",
  port: "54321",
  technicalUser: "proxy_user_1",
  technicalPassword: "proxy_pass",
} as const

export const TEST_TEAM = {
  name: "Engineering",
} as const

export const ADMIN_USER = {
  username: "admin",
  password: "admin123",
  email: "admin@example.com",
} as const

export const REGULAR_USER = {
  username: "user",
  password: "user123",
  email: "user@example.com",
} as const
