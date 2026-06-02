package com.secops.service;

import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.entity.Target;
import com.secops.entity.enums.TargetType;
import com.secops.repository.TargetRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.time.LocalDateTime;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TargetService {

    private final TargetRepository targetRepository;

    @Transactional(readOnly = true)
    public List<TargetDTO> listAll() {
        return targetRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public TargetDTO create(CreateTargetRequest request) {
        if (targetRepository.existsByDomain(request.getDomain())) {
            throw new IllegalArgumentException("该目标已存在");
        }

        Target target = new Target();
        target.setDomain(request.getDomain());
        target.setType(TargetType.valueOf(request.getType()));

        // IP 类型直接标记为已验证，无需 DNS TXT 验证
        if (target.getType() == TargetType.IP) {
            target.setVerified(true);
        } else {
            target.setTxtRecord("secops-verify=" + UUID.randomUUID());
        }

        Target saved = targetRepository.save(target);
        return toDTO(saved);
    }

    @Transactional
    public TargetDTO verify(Long id) {
        Target target = targetRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("目标不存在"));

        if (target.isVerified()) {
            return toDTO(target);
        }

        // IP 类型直接通过验证
        if (target.getType() == TargetType.IP) {
            target.setVerified(true);
            target.setTxtVerifiedAt(LocalDateTime.now());
            targetRepository.save(target);
            return toDTO(target);
        }

        boolean verified = checkTxtRecord(target.getDomain(), target.getTxtRecord());
        if (verified) {
            target.setVerified(true);
            target.setTxtVerifiedAt(LocalDateTime.now());
            targetRepository.save(target);
        }

        return toDTO(target);
    }

    @Transactional
    public void delete(Long id) {
        if (!targetRepository.existsById(id)) {
            throw new EntityNotFoundException("目标不存在");
        }
        targetRepository.deleteById(id);
    }

    private boolean checkTxtRecord(String domain, String expectedTxt) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"TXT"});
            String txt = attrs.get("TXT") != null ? attrs.get("TXT").get().toString() : "";
            ctx.close();

            return txt.contains(expectedTxt);
        } catch (Exception e) {
            return false;
        }
    }

    private TargetDTO toDTO(Target target) {
        TargetDTO dto = new TargetDTO();
        dto.setId(target.getId());
        dto.setType(target.getType());
        dto.setDomain(target.getDomain());
        dto.setVerified(target.isVerified());
        dto.setTxtRecord(target.getTxtRecord());
        dto.setTxtVerifiedAt(target.getTxtVerifiedAt());
        dto.setSubdomains(target.getSubdomains());
        dto.setPorts(target.getPorts());
        dto.setLastScanAt(target.getLastScanAt());
        dto.setCreatedAt(target.getCreatedAt());
        dto.setUpdatedAt(target.getUpdatedAt());
        return dto;
    }
}
