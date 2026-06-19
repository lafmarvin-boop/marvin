import time
import uiautomator2 as u2
from config import (
    APP_PACKAGE, APP_ACTIVITY,
    REGISTER_SELECTORS, LOGIN_SELECTORS,
    TIMEOUT, DELAY_BETWEEN_ACTIONS,
)


class AndroidAutomator:
    def __init__(self, device_serial: str | None = None):
        """
        device_serial : numéro de série ADB (ex: 'emulator-5554').
        Si None, se connecte au premier appareil disponible.
        """
        self.d = u2.connect(device_serial)
        print(f"Connecté à : {self.d.serial} ({self.d.info['productName']})")

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    def _find(self, selector: dict):
        return self.d(**selector)

    def _tap(self, selector: dict) -> None:
        el = self._find(selector)
        el.wait(timeout=TIMEOUT)
        el.click()
        time.sleep(DELAY_BETWEEN_ACTIONS)

    def _type(self, selector: dict, text: str) -> None:
        el = self._find(selector)
        el.wait(timeout=TIMEOUT)
        el.clear_text()
        el.set_text(text)
        time.sleep(DELAY_BETWEEN_ACTIONS)

    def _wait_for(self, selector: dict, timeout: int = TIMEOUT) -> bool:
        return self._find(selector).wait(timeout=timeout)

    def _launch_app(self) -> None:
        self.d.app_start(APP_PACKAGE, activity=APP_ACTIVITY, wait=True)
        time.sleep(2)

    def _stop_app(self) -> None:
        self.d.app_stop(APP_PACKAGE)
        time.sleep(1)

    # ------------------------------------------------------------------
    # Création de compte
    # ------------------------------------------------------------------

    def register(self, account: dict) -> bool:
        """
        Crée un compte via l'UI.
        Retourne True si succès, False sinon.
        """
        try:
            self._launch_app()

            # Aller sur l'écran d'inscription si nécessaire
            if self._find(REGISTER_SELECTORS["open_register_button"]).exists(timeout=3):
                self._tap(REGISTER_SELECTORS["open_register_button"])

            # Remplir le formulaire
            self._type(REGISTER_SELECTORS["username_field"], account["username"])
            self._type(REGISTER_SELECTORS["email_field"], account["email"])
            self._type(REGISTER_SELECTORS["password_field"], account["password"])

            # Champ confirmation mot de passe (optionnel)
            if self._find(REGISTER_SELECTORS["confirm_password"]).exists(timeout=2):
                self._type(REGISTER_SELECTORS["confirm_password"], account["password"])

            self._tap(REGISTER_SELECTORS["submit_button"])

            # Vérifier le succès
            success = self._wait_for(REGISTER_SELECTORS["success_indicator"], timeout=15)
            return success

        except Exception as e:
            print(f"  Erreur lors de l'inscription : {e}")
            return False
        finally:
            self._stop_app()

    # ------------------------------------------------------------------
    # Connexion
    # ------------------------------------------------------------------

    def login(self, account: dict) -> bool:
        """
        Connecte un compte existant.
        Retourne True si succès.
        """
        try:
            self._launch_app()
            self._type(LOGIN_SELECTORS["email_field"], account["email"])
            self._type(LOGIN_SELECTORS["password_field"], account["password"])
            self._tap(LOGIN_SELECTORS["submit_button"])
            return self._wait_for(LOGIN_SELECTORS["success_indicator"], timeout=15)
        except Exception as e:
            print(f"  Erreur lors de la connexion : {e}")
            return False
        finally:
            self._stop_app()

    # ------------------------------------------------------------------
    # Actions personnalisées après connexion
    # ------------------------------------------------------------------

    def run_actions(self, account: dict, actions: list[dict]) -> None:
        """
        Exécute une liste d'actions après connexion.
        Format d'une action : {"type": "tap"|"type"|"wait", "selector": {...}, "text": "..."}
        """
        try:
            self._launch_app()
            if not self.login(account):
                print("  Connexion échouée, actions annulées.")
                return
            for action in actions:
                if action["type"] == "tap":
                    self._tap(action["selector"])
                elif action["type"] == "type":
                    self._type(action["selector"], action["text"])
                elif action["type"] == "wait":
                    time.sleep(action.get("seconds", 1))
        except Exception as e:
            print(f"  Erreur lors des actions : {e}")
        finally:
            self._stop_app()
