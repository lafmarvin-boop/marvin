#!/usr/bin/env python3
"""
Marvin Android Automator
Création et gestion de comptes en masse pour vos applications Android.

Usage:
    python main.py create --count 10
    python main.py create --count 5 --device emulator-5554
    python main.py login --email user@example.com
    python main.py list
    python main.py list --status active
    python main.py export
    python main.py stats
"""

import time
import click
from account_manager import init_db, save_account, mark_status, get_accounts, export_csv, stats
from generators import generate_account
from automator import AndroidAutomator
from config import DELAY_BETWEEN_ACCOUNTS


@click.group()
def cli():
    """Marvin - Automation de comptes Android."""
    init_db()


# ------------------------------------------------------------------
# Commande : créer des comptes en masse
# ------------------------------------------------------------------

@cli.command()
@click.option("--count", "-n", default=1, help="Nombre de comptes à créer")
@click.option("--device", "-d", default=None, help="Serial ADB du device (défaut: premier disponible)")
@click.option("--dry-run", is_flag=True, help="Génère les données sans toucher au device")
def create(count: int, device: str | None, dry_run: bool):
    """Crée N comptes via l'interface de votre app Android."""
    automator = None if dry_run else AndroidAutomator(device)
    created, failed = 0, 0

    for i in range(1, count + 1):
        account = generate_account(index=i)
        click.echo(f"\n[{i}/{count}] {account['username']} ({account['email']})")

        if dry_run:
            click.echo("  [dry-run] Données générées, pas d'action sur le device.")
            save_account(account)
            created += 1
            continue

        account_id = save_account(account)
        success = automator.register(account)

        if success:
            mark_status(account_id, "active")
            click.echo(f"  Compte créé avec succès.")
            created += 1
        else:
            mark_status(account_id, "failed")
            click.echo(f"  Échec de la création.")
            failed += 1

        if i < count:
            time.sleep(DELAY_BETWEEN_ACCOUNTS)

    click.echo(f"\nTerminé : {created} créés, {failed} échoués.")


# ------------------------------------------------------------------
# Commande : connexion d'un compte
# ------------------------------------------------------------------

@cli.command()
@click.option("--email", required=True, help="Email du compte à connecter")
@click.option("--device", "-d", default=None, help="Serial ADB du device")
def login(email: str, device: str | None):
    """Connecte un compte existant sur le device."""
    accounts = get_accounts(status="active")
    account = next((a for a in accounts if a["email"] == email), None)
    if not account:
        click.echo(f"Compte '{email}' introuvable ou inactif.")
        return
    automator = AndroidAutomator(device)
    success = automator.login(account)
    click.echo("Connecté avec succès." if success else "Échec de la connexion.")


# ------------------------------------------------------------------
# Commande : liste des comptes
# ------------------------------------------------------------------

@cli.command("list")
@click.option("--status", default=None, help="Filtrer par statut: created | active | failed")
def list_accounts(status: str | None):
    """Affiche la liste des comptes enregistrés."""
    accounts = get_accounts(status)
    if not accounts:
        click.echo("Aucun compte trouvé.")
        return
    click.echo(f"\n{'ID':<5} {'Email':<35} {'Username':<20} {'Statut':<10} {'Créé le'}")
    click.echo("-" * 90)
    for a in accounts:
        click.echo(f"{a['id']:<5} {a['email']:<35} {a['username']:<20} {a['status']:<10} {a['created_at']}")


# ------------------------------------------------------------------
# Commande : export CSV
# ------------------------------------------------------------------

@cli.command()
@click.option("--output", "-o", default="accounts_export.csv", help="Fichier de sortie")
def export(output: str):
    """Exporte tous les comptes en CSV."""
    export_csv(output)


# ------------------------------------------------------------------
# Commande : statistiques
# ------------------------------------------------------------------

@cli.command()
def stats_cmd():
    """Affiche les statistiques des comptes."""
    data = stats()
    total = sum(data.values())
    click.echo(f"\nTotal comptes : {total}")
    for status, count in data.items():
        click.echo(f"  {status:<10} : {count}")


cli.add_command(stats_cmd, name="stats")


if __name__ == "__main__":
    cli()
