package io.github.tinkerlgd2026.agentscope.cls.demo.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

public final class TravelBudgetTools {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Tool(
            name = "calculate_budget",
            description =
                    "Calculate a deterministic travel budget breakdown. Use this before presenting"
                            + " a total travel cost.")
    public String calculateBudget(
            @ToolParam(name = "travelers", description = "Number of travelers") int travelers,
            @ToolParam(name = "days", description = "Number of travel days") int days,
            @ToolParam(name = "hotel_nights", description = "Number of hotel nights")
                    int hotelNights,
            @ToolParam(name = "hotel_per_night", description = "Hotel cost per night in CNY")
                    long hotelPerNight,
            @ToolParam(
                            name = "food_per_person_per_day",
                            description = "Food cost per person per day in CNY")
                    long foodPerPersonPerDay,
            @ToolParam(
                            name = "local_transport_total",
                            description = "Total local transportation cost in CNY")
                    long localTransportTotal,
            @ToolParam(name = "tickets_total", description = "Total attraction ticket cost in CNY")
                    long ticketsTotal) {
        if (travelers <= 0 || days <= 0 || hotelNights < 0) {
            throw new IllegalArgumentException(
                    "travelers and days must be positive; hotel_nights must not be negative");
        }
        if (hotelPerNight < 0
                || foodPerPersonPerDay < 0
                || localTransportTotal < 0
                || ticketsTotal < 0) {
            throw new IllegalArgumentException("budget amounts must not be negative");
        }
        long lodging = Math.multiplyExact(hotelNights, hotelPerNight);
        long food = Math.multiplyExact(Math.multiplyExact(travelers, days), foodPerPersonPerDay);
        long total =
                Math.addExact(
                        Math.addExact(lodging, food),
                        Math.addExact(localTransportTotal, ticketsTotal));
        ObjectNode result = JSON.createObjectNode();
        result.put("currency", "CNY");
        result.put("travelers", travelers);
        result.put("days", days);
        result.put("lodging", lodging);
        result.put("food", food);
        result.put("local_transport", localTransportTotal);
        result.put("tickets", ticketsTotal);
        result.put("total", total);
        return result.toString();
    }
}
