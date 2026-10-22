export type CurrentSubject = { subject: string; roles: string[] };

export async function getCurrentSubject(
  token: () => Promise<string>,
  signal?: AbortSignal,
): Promise<CurrentSubject> {
  const response = await fetch(
    `${import.meta.env.VITE_API_URL ?? "http://localhost:8280"}/api/v1/customers/me`,
    {
      headers: { Authorization: `Bearer ${await token()}` },
      signal,
    },
  );
  if (!response.ok) {
    if (response.status === 401)
      throw new Error("Your session has expired. Sign in again.");
    if (response.status === 403)
      throw new Error("This identity cannot access the customer workspace.");
    throw new Error(
      "We could not connect to your workspace. Please try again.",
    );
  }
  return response.json() as Promise<CurrentSubject>;
}
