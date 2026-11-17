package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.catalog.events.LayoutRequest.PriceTier;
import com.ticketflow.catalog.events.LayoutRequest.Section;
import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The rules for a venue layout (FR-3, NFR-10), without a database. */
class LayoutRequestTest {

    private static LayoutRequest valid() {
        return new LayoutRequest("UZS",
                List.of(new PriceTier("Standard", 150_000L), new PriceTier("VIP", 400_000L)),
                List.of(new Section("Stalls", "Standard", 10, 20), new Section("Front", "VIP", 2, 10)));
    }

    private static List<String> fieldsOf(LayoutRequest request) {
        try {
            request.validated();
        } catch (InvalidRequestException e) {
            return e.errors().stream().map(FieldProblem::field).toList();
        }
        return List.of();
    }

    @Test
    void aGoodLayoutIsAccepted() {
        assertThat(fieldsOf(valid())).isEmpty();
    }

    @Test
    void namesAreTrimmedAndATierIsFoundWithoutRegardToCase() {
        LayoutRequest request = new LayoutRequest("USD",
                List.of(new PriceTier("  VIP  ", 100L)),
                List.of(new Section("  Balcony ", "vip", 1, 1)));

        LayoutRequest clean = request.validated();

        assertThat(clean.priceTiers().get(0).name()).isEqualTo("VIP");
        assertThat(clean.sections().get(0).name()).isEqualTo("Balcony");
        assertThat(clean.sections().get(0).tier()).isEqualTo("VIP");
    }

    @Test
    void theCurrencyMustBeOneWeSupport() {
        assertThat(fieldsOf(new LayoutRequest(null, valid().priceTiers(), valid().sections())))
                .containsExactly("currency");
        assertThat(fieldsOf(new LayoutRequest("usd", valid().priceTiers(), valid().sections())))
                .containsExactly("currency");
        assertThat(fieldsOf(new LayoutRequest("GBP", valid().priceTiers(), valid().sections())))
                .containsExactly("currency");
    }

    @Test
    void thereMustBeTiersAndSections() {
        // Without tiers the sections cannot name one either, so those problems are reported too.
        assertThat(fieldsOf(new LayoutRequest("UZS", null, valid().sections()))).contains("priceTiers");
        assertThat(fieldsOf(new LayoutRequest("UZS", List.of(), valid().sections()))).contains("priceTiers");
        assertThat(fieldsOf(new LayoutRequest("UZS", valid().priceTiers(), null))).containsExactly("sections");
        assertThat(fieldsOf(new LayoutRequest("UZS", valid().priceTiers(), List.of()))).containsExactly("sections");
    }

    @Test
    void aPriceMustBeAPositiveAmountWithinTheLimit() {
        for (Long price : new Long[] {null, 0L, -5L, LayoutRequest.MAX_PRICE_MINOR + 1}) {
            LayoutRequest request = new LayoutRequest("UZS", List.of(new PriceTier("Standard", price)),
                    List.of(new Section("Stalls", "Standard", 1, 1)));

            assertThat(fieldsOf(request)).as("price " + price).containsExactly("priceTiers[0].priceMinor");
        }
    }

    @Test
    void tierNamesMustBeUniqueWithoutRegardToCase() {
        LayoutRequest request = new LayoutRequest("UZS",
                List.of(new PriceTier("VIP", 1L), new PriceTier("vip", 2L)),
                List.of(new Section("Stalls", "VIP", 1, 1)));

        assertThat(fieldsOf(request)).containsExactly("priceTiers[1].name");
    }

    @Test
    void aSectionMustUseAnExistingTier() {
        LayoutRequest request = new LayoutRequest("UZS", valid().priceTiers(),
                List.of(new Section("Stalls", "Gold", 1, 1)));

        assertThat(fieldsOf(request)).containsExactly("sections[0].tier");
    }

    @Test
    void sectionNamesMustBeUnique() {
        LayoutRequest request = new LayoutRequest("UZS", valid().priceTiers(),
                List.of(new Section("Stalls", "Standard", 1, 1), new Section("STALLS", "VIP", 1, 1)));

        assertThat(fieldsOf(request)).containsExactly("sections[1].name");
    }

    @Test
    void rowsAndSeatsPerRowAreLimited() {
        for (int rows : new int[] {0, 27}) {
            LayoutRequest request = new LayoutRequest("UZS", valid().priceTiers(),
                    List.of(new Section("Stalls", "Standard", rows, 5)));
            assertThat(fieldsOf(request)).as("rows " + rows).containsExactly("sections[0].rows");
        }
        for (int perRow : new int[] {0, 101}) {
            LayoutRequest request = new LayoutRequest("UZS", valid().priceTiers(),
                    List.of(new Section("Stalls", "Standard", 5, perRow)));
            assertThat(fieldsOf(request)).as("seatsPerRow " + perRow).containsExactly("sections[0].seatsPerRow");
        }
        LayoutRequest missing = new LayoutRequest("UZS", valid().priceTiers(),
                List.of(new Section("Stalls", "Standard", null, null)));
        assertThat(fieldsOf(missing)).containsExactly("sections[0].rows", "sections[0].seatsPerRow");
    }

    @Test
    void theLargestAllowedVenueIsAccepted() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sections.add(new Section("Block " + i, "Standard", 10, 100)); // 1,000 seats each: 5,000 in total
        }

        assertThat(fieldsOf(new LayoutRequest("UZS", valid().priceTiers(), sections))).isEmpty();
    }

    @Test
    void moreThan5000SeatsInTotalAreRefused() {
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            sections.add(new Section("Block " + i, "Standard", 10, 100)); // 6,000 seats
        }

        assertThat(fieldsOf(new LayoutRequest("UZS", valid().priceTiers(), sections))).containsExactly("sections");
    }

    @Test
    void theNumberOfTiersAndSectionsIsLimited() {
        List<PriceTier> tiers = new ArrayList<>();
        for (int i = 0; i <= LayoutRequest.MAX_TIERS; i++) {
            tiers.add(new PriceTier("Tier " + i, 1L));
        }
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i <= LayoutRequest.MAX_SECTIONS; i++) {
            sections.add(new Section("Section " + i, "Standard", 1, 1));
        }

        assertThat(fieldsOf(new LayoutRequest("UZS", tiers, valid().sections()))).contains("priceTiers");
        assertThat(fieldsOf(new LayoutRequest("UZS", valid().priceTiers(), sections))).containsExactly("sections");
    }

    @Test
    void namesMustBePlainText() {
        LayoutRequest request = new LayoutRequest("UZS",
                List.of(new PriceTier("Two\nlines", 1L)),
                List.of(new Section("x".repeat(51), "Two\nlines", 1, 1)));

        assertThat(fieldsOf(request)).contains("priceTiers[0].name", "sections[0].name");
    }

    @Test
    void emptyEntriesInTheListsAreReportedNotCrashedOn() {
        List<PriceTier> tiers = new ArrayList<>();
        tiers.add(null);
        List<Section> sections = new ArrayList<>();
        sections.add(null);

        assertThat(fieldsOf(new LayoutRequest("UZS", tiers, sections))).containsExactly("priceTiers[0]", "sections[0]");
        assertThatThrownBy(() -> new LayoutRequest("UZS", tiers, sections).validated())
                .isInstanceOf(InvalidRequestException.class);
    }
}
