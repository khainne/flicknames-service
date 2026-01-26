package com.flicknames.service.collector.ssa;

import com.flicknames.service.entity.SsaImportMetadata;
import com.flicknames.service.entity.SsaName;
import com.flicknames.service.entity.SsaNameStateBreakdown;
import com.flicknames.service.entity.SsaNameYearlyStat;
import com.flicknames.service.repository.SsaImportMetadataRepository;
import com.flicknames.service.repository.SsaNameRepository;
import com.flicknames.service.repository.SsaNameStateBreakdownRepository;
import com.flicknames.service.repository.SsaNameYearlyStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class SsaImportService {

    private static final String NATIONAL_DATA_URL = "https://www.ssa.gov/oact/babynames/names.zip";
    private static final String STATE_DATA_URL = "https://www.ssa.gov/oact/babynames/state/namesbystate.zip";
    private static final int BATCH_SIZE = 1000;

    private final SsaNameRepository ssaNameRepository;
    private final SsaNameYearlyStatRepository yearlyStatRepository;
    private final SsaNameStateBreakdownRepository stateBreakdownRepository;
    private final SsaImportMetadataRepository importMetadataRepository;

    // In-memory caches for efficient lookups during import
    private final Map<String, SsaName> nameCache = new HashMap<>();
    private final Map<String, SsaNameYearlyStat> yearlyStatCache = new HashMap<>();

    /**
     * Import national SSA data from remote URL
     */
    public SsaImportResult importNationalData(boolean forceReimport) {
        return importNationalData(forceReimport, null, null);
    }

    /**
     * Import national SSA data with optional year filtering
     */
    public SsaImportResult importNationalData(boolean forceReimport, Integer minYear, Integer maxYear) {
        return importNationalDataFromUrl(NATIONAL_DATA_URL, forceReimport, minYear, maxYear);
    }

    /**
     * Import national SSA data from custom URL
     */
    public SsaImportResult importNationalDataFromUrl(String sourceUrl, boolean forceReimport, Integer minYear, Integer maxYear) {
        log.info("Starting national SSA data import from {} (force={}, years={}-{})", sourceUrl, forceReimport, minYear, maxYear);
        long startTime = System.currentTimeMillis();

        SsaImportMetadata metadata = SsaImportMetadata.builder()
                .datasetType(SsaImportMetadata.DatasetType.NATIONAL)
                .sourceUrl(sourceUrl)
                .status(SsaImportMetadata.ImportStatus.IN_PROGRESS)
                .build();
        metadata = importMetadataRepository.save(metadata);

        try {
            // Download the ZIP file
            Path zipFile = downloadFile(sourceUrl);
            String checksum = calculateChecksum(zipFile);

            // Check if already imported (unless forcing)
            if (!forceReimport && importMetadataRepository.findByFileChecksumAndDatasetType(
                    checksum, SsaImportMetadata.DatasetType.NATIONAL).isPresent()) {
                log.info("National data already imported (checksum match), skipping");
                metadata.setStatus(SsaImportMetadata.ImportStatus.SUCCESS);
                metadata.setFileChecksum(checksum);
                metadata.setErrorMessage("Skipped - already imported");
                importMetadataRepository.save(metadata);
                Files.deleteIfExists(zipFile);
                return new SsaImportResult(0, 0, "Already imported");
            }

            metadata.setFileChecksum(checksum);

            // Clear caches for fresh import
            nameCache.clear();
            yearlyStatCache.clear();

            // Load existing names and stats into cache to avoid duplicates
            loadExistingNamesIntoCache();
            loadExistingYearlyStatsIntoCache();

            // Parse and import the data
            SsaImportResult result = parseAndImportNationalZip(zipFile, minYear, maxYear);

            // Update metadata
            metadata.setStatus(SsaImportMetadata.ImportStatus.SUCCESS);
            metadata.setRecordCount(result.recordCount());
            metadata.setNameCount(result.nameCount());
            metadata.setDataYear(result.maxYear());
            metadata.setImportDurationMs(System.currentTimeMillis() - startTime);
            importMetadataRepository.save(metadata);

            // Cleanup
            Files.deleteIfExists(zipFile);

            log.info("National import complete: {} records, {} names in {}ms",
                    result.recordCount(), result.nameCount(), metadata.getImportDurationMs());

            return result;

        } catch (Exception e) {
            log.error("Failed to import national SSA data", e);
            metadata.setStatus(SsaImportMetadata.ImportStatus.FAILED);
            metadata.setErrorMessage(e.getMessage());
            metadata.setImportDurationMs(System.currentTimeMillis() - startTime);
            importMetadataRepository.save(metadata);
            throw new RuntimeException("Failed to import national SSA data", e);
        }
    }

    /**
     * Import state-level SSA data from remote URL.
     * Must be called after national data is imported.
     */
    public SsaImportResult importStateData(boolean forceReimport) {
        return importStateData(forceReimport, null, null);
    }

    /**
     * Import state-level SSA data with optional year filtering
     */
    public SsaImportResult importStateData(boolean forceReimport, Integer minYear, Integer maxYear) {
        return importStateDataFromUrl(STATE_DATA_URL, forceReimport, minYear, maxYear);
    }

    /**
     * Import state-level SSA data from custom URL
     */
    public SsaImportResult importStateDataFromUrl(String sourceUrl, boolean forceReimport, Integer minYear, Integer maxYear) {
        log.info("Starting state SSA data import from {} (force={}, years={}-{})", sourceUrl, forceReimport, minYear, maxYear);
        long startTime = System.currentTimeMillis();

        SsaImportMetadata metadata = SsaImportMetadata.builder()
                .datasetType(SsaImportMetadata.DatasetType.STATE)
                .sourceUrl(sourceUrl)
                .status(SsaImportMetadata.ImportStatus.IN_PROGRESS)
                .build();
        metadata = importMetadataRepository.save(metadata);

        try {
            // Download the ZIP file
            Path zipFile = downloadFile(sourceUrl);
            String checksum = calculateChecksum(zipFile);

            // Check if already imported (unless forcing)
            if (!forceReimport && importMetadataRepository.findByFileChecksumAndDatasetType(
                    checksum, SsaImportMetadata.DatasetType.STATE).isPresent()) {
                log.info("State data already imported (checksum match), skipping");
                metadata.setStatus(SsaImportMetadata.ImportStatus.SUCCESS);
                metadata.setFileChecksum(checksum);
                metadata.setErrorMessage("Skipped - already imported");
                importMetadataRepository.save(metadata);
                Files.deleteIfExists(zipFile);
                return new SsaImportResult(0, 0, "Already imported");
            }

            metadata.setFileChecksum(checksum);

            // Clear caches and load only the year range being imported
            nameCache.clear();
            yearlyStatCache.clear();
            loadExistingNamesIntoCache();

            // Load yearly stats for only the years being imported (much faster than loading all 2.1M records)
            loadYearlyStatsByYearRange(minYear, maxYear);

            // Parse and import the data
            SsaImportResult result = parseAndImportStateZip(zipFile, minYear, maxYear);

            // Update metadata
            metadata.setStatus(SsaImportMetadata.ImportStatus.SUCCESS);
            metadata.setRecordCount(result.recordCount());
            metadata.setDataYear(result.maxYear());
            metadata.setImportDurationMs(System.currentTimeMillis() - startTime);
            importMetadataRepository.save(metadata);

            // Cleanup
            Files.deleteIfExists(zipFile);

            log.info("State import complete: {} records in {}ms",
                    result.recordCount(), metadata.getImportDurationMs());

            return result;

        } catch (Exception e) {
            log.error("Failed to import state SSA data", e);
            metadata.setStatus(SsaImportMetadata.ImportStatus.FAILED);
            metadata.setErrorMessage(e.getMessage());
            metadata.setImportDurationMs(System.currentTimeMillis() - startTime);
            importMetadataRepository.save(metadata);
            throw new RuntimeException("Failed to import state SSA data", e);
        }
    }

    /**
     * Import from a local ZIP file (for testing or manual imports)
     */
    public SsaImportResult importFromLocalFile(Path zipFile, SsaImportMetadata.DatasetType type,
                                                Integer minYear, Integer maxYear) {
        log.info("Importing {} data from local file: {}", type, zipFile);

        nameCache.clear();
        yearlyStatCache.clear();
        loadExistingNamesIntoCache();

        if (type == SsaImportMetadata.DatasetType.NATIONAL) {
            loadExistingYearlyStatsIntoCache();
            return parseAndImportNationalZip(zipFile, minYear, maxYear);
        } else {
            // State import: load only the year range being imported
            loadYearlyStatsByYearRange(minYear, maxYear);
            return parseAndImportStateZip(zipFile, minYear, maxYear);
        }
    }

    /**
     * Calculate rankings for a specific year.
     * Should be called after import to populate rank and rankChange fields.
     */
    @Transactional
    public void calculateRankings(Integer year) {
        log.info("Calculating rankings for year {}", year);

        for (String sex : List.of("M", "F")) {
            // Get all stats for this year+sex, ordered by count desc
            List<SsaNameYearlyStat> stats = yearlyStatRepository.findAllByYearAndSexOrderByCountDesc(year, sex);

            // Calculate total births for proportion
            long totalBirths = stats.stream().mapToLong(SsaNameYearlyStat::getCount).sum();

            // Assign ranks
            int rank = 1;
            for (SsaNameYearlyStat stat : stats) {
                stat.setRank(rank);

                // Calculate proportion
                if (totalBirths > 0) {
                    BigDecimal proportion = BigDecimal.valueOf(stat.getCount())
                            .divide(BigDecimal.valueOf(totalBirths), 8, RoundingMode.HALF_UP);
                    stat.setProportion(proportion);
                }

                rank++;
            }

            yearlyStatRepository.saveAll(stats);
            log.info("Calculated rankings for {} {} names in {}", stats.size(), sex, year);
        }

        // Calculate rank changes (compared to previous year)
        calculateRankChanges(year);
    }

    /**
     * Calculate rank changes compared to previous year
     */
    @Transactional
    public void calculateRankChanges(Integer year) {
        Integer previousYear = year - 1;

        for (String sex : List.of("M", "F")) {
            List<SsaNameYearlyStat> currentStats = yearlyStatRepository.findAllByYearAndSexOrderByCountDesc(year, sex);
            Map<String, Integer> previousRanks = new HashMap<>();

            // Build map of previous year's ranks
            List<SsaNameYearlyStat> prevStats = yearlyStatRepository.findAllByYearAndSexOrderByCountDesc(previousYear, sex);
            for (SsaNameYearlyStat stat : prevStats) {
                if (stat.getRank() != null) {
                    previousRanks.put(stat.getSsaName().getName(), stat.getRank());
                }
            }

            // Calculate rank change
            for (SsaNameYearlyStat stat : currentStats) {
                Integer prevRank = previousRanks.get(stat.getSsaName().getName());
                if (prevRank != null && stat.getRank() != null) {
                    // Positive = improvement (lower rank number is better)
                    stat.setRankChange(prevRank - stat.getRank());
                }
            }

            yearlyStatRepository.saveAll(currentStats);
        }

        log.info("Calculated rank changes for year {} vs {}", year, previousYear);
    }

    /**
     * Calculate state-level rankings for a specific year and state
     */
    @Transactional
    public void calculateStateRankings(Integer year, String stateCode) {
        log.info("Calculating state rankings for {} in {}", stateCode, year);

        for (String sex : List.of("M", "F")) {
            List<SsaNameStateBreakdown> breakdowns =
                    stateBreakdownRepository.findAllByStateAndYearAndSexOrderByCountDesc(stateCode, year, sex);

            int rank = 1;
            for (SsaNameStateBreakdown breakdown : breakdowns) {
                breakdown.setRank(rank);
                rank++;
            }

            stateBreakdownRepository.saveAll(breakdowns);
        }
    }

    /**
     * Get import status/history
     */
    public List<SsaImportMetadata> getImportHistory() {
        return importMetadataRepository.findAllByOrderByImportedAtDesc();
    }

    /**
     * Check if new data might be available (based on time since last import)
     */
    public boolean isUpdateLikelyAvailable() {
        List<SsaImportMetadata> recent = importMetadataRepository.findLatestSuccessfulImport(
                SsaImportMetadata.DatasetType.NATIONAL);

        if (recent.isEmpty()) {
            return true; // Never imported
        }

        SsaImportMetadata latest = recent.get(0);
        // SSA typically releases new data in May each year
        int currentYear = java.time.Year.now().getValue();
        return latest.getDataYear() == null || latest.getDataYear() < currentYear - 1;
    }

    // ==================== Private Helper Methods ====================

    private Path downloadFile(String url) throws IOException, InterruptedException {
        log.info("Downloading file from {}", url);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Referer", "https://www.ssa.gov/")
                .GET()
                .build();

        Path tempFile = Files.createTempFile("ssa-names-", ".zip");

        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempFile));

        if (response.statusCode() != 200) {
            Files.deleteIfExists(tempFile);
            throw new IOException("Failed to download file: HTTP " + response.statusCode());
        }

        log.info("Downloaded {} bytes to {}", Files.size(tempFile), tempFile);
        return tempFile;
    }

    private String calculateChecksum(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            try (InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Failed to calculate checksum", e);
        }
    }

    private void loadExistingNamesIntoCache() {
        log.info("Loading existing SSA names into cache...");
        List<SsaName> existingNames = ssaNameRepository.findAll();
        for (SsaName name : existingNames) {
            nameCache.put(makeCacheKey(name.getName(), name.getSex()), name);
        }
        log.info("Loaded {} existing names into cache", nameCache.size());
    }

    private void loadExistingYearlyStatsIntoCache() {
        log.info("Loading existing yearly stats into cache...");
        List<SsaNameYearlyStat> existingStats = yearlyStatRepository.findAll();
        for (SsaNameYearlyStat stat : existingStats) {
            String key = makeYearlyStatCacheKey(stat.getSsaName().getName(), stat.getSsaName().getSex(), stat.getYear());
            yearlyStatCache.put(key, stat);
        }
        log.info("Loaded {} existing yearly stats into cache", yearlyStatCache.size());
    }

    private void loadYearlyStatsByYearRange(Integer minYear, Integer maxYear) {
        if (minYear == null) minYear = 1880;
        if (maxYear == null) maxYear = java.time.Year.now().getValue();

        log.info("Loading yearly stats for year range {}-{} into cache...", minYear, maxYear);
        List<SsaNameYearlyStat> stats = yearlyStatRepository.findAllByYearRange(minYear, maxYear);
        for (SsaNameYearlyStat stat : stats) {
            String key = makeYearlyStatCacheKey(stat.getSsaName().getName(), stat.getSsaName().getSex(), stat.getYear());
            yearlyStatCache.put(key, stat);
        }
        log.info("Loaded {} yearly stats for years {}-{} into cache", yearlyStatCache.size(), minYear, maxYear);
    }

    private String makeCacheKey(String name, String sex) {
        return name.toUpperCase() + "|" + sex;
    }

    private String makeYearlyStatCacheKey(String name, String sex, Integer year) {
        return name.toUpperCase() + "|" + sex + "|" + year;
    }

    /**
     * Parse and import national data from ZIP file.
     * National files are named yobYYYY.txt with format: name,sex,count
     * Note: Not transactional - batches are committed independently
     */
    private SsaImportResult parseAndImportNationalZip(Path zipFile, Integer minYear, Integer maxYear) {
        long recordCount = 0;
        Set<String> uniqueNames = new HashSet<>();
        int maxYearFound = 0;

        List<SsaName> nameBatch = new ArrayList<>();
        List<SsaNameYearlyStat> statBatch = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String fileName = entry.getName();

                // Skip non-data files
                if (!fileName.matches("yob\\d{4}\\.txt")) {
                    continue;
                }

                // Extract year from filename
                int year = Integer.parseInt(fileName.substring(3, 7));

                // Apply year filter
                if (minYear != null && year < minYear) continue;
                if (maxYear != null && year > maxYear) continue;

                maxYearFound = Math.max(maxYearFound, year);

                // Read the file content (don't close the stream)
                BufferedReader reader = new BufferedReader(new InputStreamReader(zis));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length != 3) continue;

                    String name = parts[0].trim();
                    String sex = parts[1].trim();
                    int count = Integer.parseInt(parts[2].trim());

                    // Get or create the SsaName
                    String cacheKey = makeCacheKey(name, sex);
                    SsaName ssaName = nameCache.get(cacheKey);

                    if (ssaName == null) {
                        ssaName = SsaName.builder()
                                .name(name)
                                .sex(sex)
                                .build();
                        nameBatch.add(ssaName);
                        nameCache.put(cacheKey, ssaName);
                        uniqueNames.add(cacheKey);

                        // Flush name batch
                        if (nameBatch.size() >= BATCH_SIZE) {
                            ssaNameRepository.saveAll(nameBatch);
                            nameBatch.clear();
                        }
                    } else {
                        uniqueNames.add(cacheKey);
                    }

                    // Check if yearly stat already exists
                    String yearlyKey = makeYearlyStatCacheKey(name, sex, year);
                    if (!yearlyStatCache.containsKey(yearlyKey)) {
                        SsaNameYearlyStat stat = SsaNameYearlyStat.builder()
                                .ssaName(ssaName)
                                .year(year)
                                .count(count)
                                .build();
                        statBatch.add(stat);
                        yearlyStatCache.put(yearlyKey, stat);

                        // Flush stat batch
                        if (statBatch.size() >= BATCH_SIZE) {
                            // Ensure names are saved first
                            if (!nameBatch.isEmpty()) {
                                ssaNameRepository.saveAll(nameBatch);
                                nameBatch.clear();
                            }
                            yearlyStatRepository.saveAll(statBatch);
                            statBatch.clear();
                        }
                    }

                    recordCount++;

                    if (recordCount % 100000 == 0) {
                        log.info("Processed {} national records...", recordCount);
                    }
                }

                zis.closeEntry();
            }

            // Flush remaining batches
            if (!nameBatch.isEmpty()) {
                ssaNameRepository.saveAll(nameBatch);
            }
            if (!statBatch.isEmpty()) {
                yearlyStatRepository.saveAll(statBatch);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse national ZIP file", e);
        }

        return new SsaImportResult(recordCount, uniqueNames.size(), "Success", maxYearFound);
    }

    /**
     * Parse and import state data from ZIP file.
     * State files are named XX.TXT with format: state,sex,year,name,count
     * Note: Not transactional - batches are committed independently
     */
    private SsaImportResult parseAndImportStateZip(Path zipFile, Integer minYear, Integer maxYear) {
        long recordCount = 0;
        int maxYearFound = 0;

        List<SsaNameStateBreakdown> breakdownBatch = new ArrayList<>();

        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String fileName = entry.getName();

                // Skip non-data files (state files are like "AK.TXT", "CA.TXT")
                if (!fileName.matches("[A-Z]{2}\\.TXT")) {
                    continue;
                }

                String stateCode = fileName.substring(0, 2);
                log.debug("Processing state file: {}", stateCode);

                BufferedReader reader = new BufferedReader(new InputStreamReader(zis));
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",");
                    if (parts.length != 5) continue;

                    String state = parts[0].trim();
                    String sex = parts[1].trim();
                    int year = Integer.parseInt(parts[2].trim());
                    String name = parts[3].trim();
                    int count = Integer.parseInt(parts[4].trim());

                    // Apply year filter
                    if (minYear != null && year < minYear) continue;
                    if (maxYear != null && year > maxYear) continue;

                    maxYearFound = Math.max(maxYearFound, year);

                    // Find the corresponding yearly stat from cache
                    String yearlyKey = makeYearlyStatCacheKey(name, sex, year);
                    SsaNameYearlyStat yearlyStat = yearlyStatCache.get(yearlyKey);

                    if (yearlyStat == null) {
                        // Name might exist in state data but not national (edge case)
                        // Skip for now - state data should be subset of national
                        continue;
                    }

                    // Create state breakdown
                    SsaNameStateBreakdown breakdown = SsaNameStateBreakdown.builder()
                            .yearlyStat(yearlyStat)
                            .stateCode(state)
                            .count(count)
                            .build();
                    breakdownBatch.add(breakdown);

                    // Flush batch
                    if (breakdownBatch.size() >= BATCH_SIZE) {
                        stateBreakdownRepository.saveAll(breakdownBatch);
                        breakdownBatch.clear();
                    }

                    recordCount++;

                    if (recordCount % 100000 == 0) {
                        log.info("Processed {} state records...", recordCount);
                    }
                }

                zis.closeEntry();
            }

            // Flush remaining batch
            if (!breakdownBatch.isEmpty()) {
                stateBreakdownRepository.saveAll(breakdownBatch);
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse state ZIP file", e);
        }

        return new SsaImportResult(recordCount, 0, "Success", maxYearFound);
    }

    /**
     * Result of an import operation
     */
    public record SsaImportResult(
            long recordCount,
            long nameCount,
            String message,
            int maxYear
    ) {
        public SsaImportResult(long recordCount, long nameCount, String message) {
            this(recordCount, nameCount, message, 0);
        }
    }
}
