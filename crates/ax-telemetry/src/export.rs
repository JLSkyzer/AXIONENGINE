//! Export JSON des métriques (R-502).

use crate::registry::{MetricKind, MetricSnapshot, Telemetry};
use core::fmt::Write as _;

/// Version du schéma de l'export.
///
/// Ce que produit cet export est lu par des outils qui ne sont pas dans ce
/// dépôt. Un document sans numéro de schéma se lit à l'aveugle, et toute
/// évolution y devient une rupture silencieuse.
pub const EXPORT_SCHEMA_VERSION: u32 = 1;

/// Écrit une chaîne JSON, échappement compris.
///
/// Les noms de métriques sont validés à la déclaration et ne peuvent rien
/// contenir qui demande un échappement ; les unités sont des littéraux du
/// dépôt. L'échappement est fait quand même : un producteur de JSON qui suppose
/// ses entrées propres finit toujours par en rencontrer une qui ne l'est pas.
fn write_string(out: &mut String, value: &str) {
    out.push('"');
    for character in value.chars() {
        match character {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            control if (control as u32) < 0x20 => {
                let _ = write!(out, "\\u{:04x}", control as u32);
            }
            other => out.push(other),
        }
    }
    out.push('"');
}

fn write_metric(out: &mut String, snapshot: &MetricSnapshot) {
    out.push_str("    {\"name\": ");
    write_string(out, &snapshot.name);
    out.push_str(", \"kind\": ");
    write_string(out, snapshot.kind.as_str());
    out.push_str(", \"unit\": ");
    write_string(out, snapshot.unit);
    let _ = write!(out, ", \"value\": {}", snapshot.value);

    // Le nombre de mesures et le maximum n'ont de sens que pour une durée ;
    // les écrire à zéro ailleurs laisserait croire à une mesure absente.
    if snapshot.kind == MetricKind::Duration {
        let _ = write!(
            out,
            ", \"count\": {}, \"max\": {}",
            snapshot.count, snapshot.max
        );
        if let Some(mean) = snapshot.mean() {
            let _ = write!(out, ", \"mean\": {mean}");
        }
    }
    out.push('}');
}

/// Produit l'export JSON de toutes les métriques (R-502).
///
/// La forme est un objet, jamais un tableau nu : un document JSON de premier
/// niveau doit pouvoir accueillir un champ de plus sans que sa lecture change.
///
/// L'export ne contient **aucune donnée de monde, de chat ou de joueur**
/// (R-442) : il ne peut pas en contenir, n'ayant accès qu'à des compteurs
/// nommés.
#[must_use]
pub fn to_json(telemetry: &Telemetry) -> String {
    let snapshots = telemetry.snapshots();

    let mut out = String::with_capacity(128 + snapshots.len() * 96);
    out.push_str("{\n");
    let _ = writeln!(out, "  \"schema_version\": {EXPORT_SCHEMA_VERSION},");
    let _ = writeln!(out, "  \"metric_count\": {},", snapshots.len());
    out.push_str("  \"metrics\": [\n");

    for (index, snapshot) in snapshots.iter().enumerate() {
        write_metric(&mut out, snapshot);
        if index + 1 < snapshots.len() {
            out.push(',');
        }
        out.push('\n');
    }

    out.push_str("  ]\n}");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::budget::BudgetMetrics;
    use ax_model::budgets::Budget;

    #[test]
    fn t201_l_export_est_un_objet_versionne() {
        let telemetry = Telemetry::builder().build();
        let json = to_json(&telemetry);

        assert!(json.starts_with('{'), "{json}");
        assert!(json.ends_with('}'), "{json}");
        assert!(json.contains("\"schema_version\": 1"), "{json}");
        assert!(json.contains("\"metric_count\": 0"), "{json}");
    }

    #[test]
    fn t201_chaque_metrique_apparait_avec_sa_valeur() {
        let mut builder = Telemetry::builder();
        let panics = builder
            .counter("axion.native.panics", "count")
            .expect("déclaration");
        let tick = builder.duration("axion.sim.tick_ns").expect("déclaration");
        let telemetry = builder.build();

        telemetry.add(panics, 2);
        telemetry.record(tick, 400);
        telemetry.record(tick, 600);

        let json = to_json(&telemetry);
        assert!(json.contains("\"name\": \"axion.native.panics\""), "{json}");
        assert!(json.contains("\"kind\": \"counter\""), "{json}");
        assert!(json.contains("\"value\": 2"), "{json}");
        assert!(json.contains("\"name\": \"axion.sim.tick_ns\""), "{json}");
        assert!(json.contains("\"kind\": \"duration\""), "{json}");
        assert!(json.contains("\"count\": 2"), "{json}");
        assert!(json.contains("\"max\": 600"), "{json}");
        assert!(json.contains("\"mean\": 500"), "{json}");
    }

    #[test]
    fn t201_un_compteur_ne_porte_ni_nombre_de_mesures_ni_maximum() {
        let mut builder = Telemetry::builder();
        builder
            .counter("axion.native.panics", "count")
            .expect("déclaration");
        let json = to_json(&builder.build());

        // Les écrire à zéro laisserait croire à une mesure qui n'existe pas.
        assert!(!json.contains("\"count\":"), "{json}");
        assert!(!json.contains("\"max\":"), "{json}");
    }

    #[test]
    fn t201_l_export_des_budgets_est_complet() {
        let mut builder = Telemetry::builder();
        let budgets = BudgetMetrics::register(&mut builder).expect("déclaration");
        let telemetry = builder.build();
        budgets.report(&telemetry, Budget::SimNsPerTick, 4_000_000, 3_000_000);

        let json = to_json(&telemetry);
        for budget in Budget::ALL {
            assert!(json.contains(&budget.consumed_metric()), "{budget} absent");
            assert!(json.contains(&budget.overrun_metric()), "{budget} absent");
        }
        assert!(json.contains("\"metric_count\": 40"), "{json}");
    }

    #[test]
    fn t201_le_json_produit_est_relisible() {
        // Pas de bibliothèque JSON dans le workspace — le cahier des charges
        // écarte serde_json du runtime jeu — donc un analyseur minimal, qui
        // suffit à prouver que le document est bien formé : guillemets
        // appariés, accolades et crochets équilibrés, aucune virgule finale.
        let mut builder = Telemetry::builder();
        let budgets = BudgetMetrics::register(&mut builder).expect("déclaration");
        let mesure = builder.duration("axion.sim.tick_ns").expect("déclaration");
        let telemetry = builder.build();
        telemetry.record(mesure, 1_234);
        budgets.report(&telemetry, Budget::AssetNsPerTick, 10, 5);

        let json = to_json(&telemetry);

        let mut profondeur = 0_i32;
        let mut dans_chaine = false;
        let mut echappe = false;
        let mut precedent_significatif = '\0';

        for character in json.chars() {
            if dans_chaine {
                if echappe {
                    echappe = false;
                } else if character == '\\' {
                    echappe = true;
                } else if character == '"' {
                    dans_chaine = false;
                }
                continue;
            }
            match character {
                '"' => dans_chaine = true,
                '{' | '[' => profondeur += 1,
                '}' | ']' => {
                    assert_ne!(
                        precedent_significatif, ',',
                        "virgule finale avant {character}"
                    );
                    profondeur -= 1;
                    assert!(profondeur >= 0, "fermeture sans ouverture");
                }
                _ => {}
            }
            if !character.is_whitespace() {
                precedent_significatif = character;
            }
        }

        assert!(!dans_chaine, "chaîne non refermée");
        assert_eq!(profondeur, 0, "accolades non équilibrées");
    }

    #[test]
    fn t201_les_caracteres_speciaux_sont_echappes() {
        let mut sortie = String::new();
        write_string(
            &mut sortie,
            "guillemet \" antislash \\ saut \n tabulation \t",
        );
        assert_eq!(
            sortie,
            "\"guillemet \\\" antislash \\\\ saut \\n tabulation \\t\""
        );

        let mut controle = String::new();
        write_string(&mut controle, "\u{1}");
        assert_eq!(controle, "\"\\u0001\"");
    }
}
