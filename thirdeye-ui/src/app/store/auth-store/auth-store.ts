import create, { SetState } from "zustand";
import { persist } from "zustand/middleware";
import { AuthStore } from "./auth-store.interfaces";

const LOCAL_STORAGE_KEY_AUTH = "LOCAL_STORAGE_KEY_AUTH";

// App store for authentication, persisted in browser local storage
export const useAuthStore = create<AuthStore>(
    persist<AuthStore>(
        (set: SetState<AuthStore>) => ({
            auth: false,
            accessToken: "",

            // Action for signing in
            setAccessToken: (token: string): void => {
                set({
                    auth: Boolean(token),
                    accessToken: token,
                });
            },

            // Action for signing out
            clearAccessToken: (): void => {
                set({
                    auth: false,
                    accessToken: "",
                });
            },
        }),
        {
            name: LOCAL_STORAGE_KEY_AUTH, // Persist in browser local storage
        }
    )
);
